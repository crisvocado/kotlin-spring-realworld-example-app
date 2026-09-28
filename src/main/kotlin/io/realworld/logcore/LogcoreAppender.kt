package io.realworld.logcore

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.AppenderBase
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant
import java.time.format.DateTimeFormatter

class LogcoreAppender : AppenderBase<ILoggingEvent>() {

    private val config = LogcoreConfig.load()
    private val transport = LogcoreHttpTransport.getInstance(config)
    private val mapper = ObjectMapper().registerKotlinModule()

    override fun append(event: ILoggingEvent) {
        if (!config.enabled && onEntryEmitted == null) return
        if (!event.level.isGreaterOrEqual(Level.toLevel(config.minSeverity, Level.ERROR))) return

        try {
            val entry = buildEntry(event)
            onEntryEmitted?.invoke(entry)
            if (config.enabled) {
                transport.enqueue(entry)
            }
        } catch (t: Throwable) {
            // Never break application
            System.err.println("[logcore] Error processing log event: ${t.message}")
        }
    }

    fun buildEntry(event: ILoggingEvent): LogcoreEntry {
        val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(event.timeStamp))
        val severity = event.level.toString()
        val rawMessage = event.formattedMessage ?: event.message ?: ""
        val message = if (rawMessage.length > 65536) rawMessage.substring(0, 65536) else rawMessage

        // MDC context & labels
        val mdc = event.mdcPropertyMap ?: emptyMap()

        // Trace resolution
        val traceId = mdc["logcore.traceId"] ?: mdc["traceId"] ?: mdc["trace_id"] ?: mdc["X-B3-TraceId"]
        val spanId = mdc["logcore.spanId"] ?: mdc["spanId"] ?: mdc["span_id"] ?: mdc["X-B3-SpanId"]
        val sampledStr = mdc["logcore.sampled"] ?: mdc["sampled"]
        val sampled = sampledStr?.toBooleanStrictOrNull() ?: true

        val trace = if (!traceId.isNullOrBlank() && !spanId.isNullOrBlank()) {
            LogcoreTrace(traceId = traceId, spanId = spanId, sampled = sampled)
        } else {
            null
        }

        // Error & parsed stack frames
        val error = extractError(event)

        // Labels
        val filteredMdc = mdc.filterKeys { !it.startsWith("logcore.") }
        val labels = if (filteredMdc.isNotEmpty()) {
            filteredMdc.entries.take(32).associate { (k, v) ->
                k.take(128) to (v?.take(1024) ?: "")
            }
        } else {
            null
        }

        val context: Map<String, Any>? = null

        val canonicalContextJson = if (context != null) mapper.writeValueAsString(context) else "{}"
        val insertId = computeInsertId(timestamp, config.service, severity, message, canonicalContextJson)

        return LogcoreEntry(
            timestamp = timestamp,
            severity = severity,
            message = message,
            service = config.service,
            env = config.env,
            insertId = insertId,
            trace = trace,
            error = error,
            labels = labels,
            context = context
        )
    }

    private fun extractError(event: ILoggingEvent): LogcoreError? {
        val rootProxy = event.throwableProxy ?: return null

        // Follow exception chain to its root and put root frames first
        val chain = mutableListOf<IThrowableProxy>()
        var curr: IThrowableProxy? = rootProxy
        while (curr != null) {
            chain.add(curr)
            curr = curr.cause
        }

        val frames = mutableListOf<LogcoreStackFrame>()
        // Iterate chain in reverse (root cause first)
        for (proxy in chain.reversed()) {
            val stepArray = proxy.stackTraceElementProxyArray ?: continue
            for (step in stepArray) {
                val element = step.stackTraceElement ?: continue
                val className = element.className ?: ""
                val methodName = element.methodName ?: ""
                val fileName = element.fileName ?: "unknown"
                val lineNumber = if (element.lineNumber > 0) element.lineNumber else 1
                val inApp = className.startsWith("io.realworld")

                frames.add(
                    LogcoreStackFrame(
                        function = if (className.isNotEmpty()) "$className.$methodName" else methodName,
                        file = fileName,
                        line = lineNumber,
                        column = 0,
                        inApp = inApp
                    )
                )
                if (frames.size >= 50) break
            }
            if (frames.size >= 50) break
        }

        return LogcoreError(
            type = rootProxy.className ?: "Error",
            message = rootProxy.message ?: "",
            stack = frames.take(50)
        )
    }

    private fun computeInsertId(
        timestamp: String,
        service: String,
        severity: String,
        message: String,
        contextJson: String
    ): String {
        val raw = "$timestamp|$service|$severity|$message|$contextJson"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(32)
        for (i in 0 until 16) {
            sb.append(String.format("%02x", digest[i]))
        }
        return sb.toString()
    }

    companion object {
        @Volatile
        var onEntryEmitted: ((LogcoreEntry) -> Unit)? = null

        fun install(context: LoggerContext = LoggerFactory.getILoggerFactory() as LoggerContext) {
            val rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME)
            if (rootLogger.getAppender("LOGCORE") == null) {
                val appender = LogcoreAppender()
                appender.context = context
                appender.name = "LOGCORE"
                appender.start()
                rootLogger.addAppender(appender)
            }
        }
    }
}
