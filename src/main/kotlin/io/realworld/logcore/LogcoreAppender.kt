package io.realworld.logcore

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import ch.qos.logback.core.AppenderBase
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.security.MessageDigest
import java.time.Instant

class LogcoreAppender : AppenderBase<ILoggingEvent>() {

    override fun start() {
        super.start()
        LogcoreTransport.start()
    }

    override fun stop() {
        LogcoreTransport.flushAll()
        LogcoreTransport.stop()
        super.stop()
    }

    override fun append(event: ILoggingEvent?) {
        if (!isStarted || event == null) return
        try {
            val minLevel = getMinLevel()
            if (!event.level.isGreaterOrEqual(minLevel)) {
                return
            }

            val entry = formatEvent(event) ?: return
            LogcoreTransport.enqueue(entry)
        } catch (t: Throwable) {
            // Never propagate exception or disrupt application flow
        }
    }

    private fun getMinLevel(): Level {
        val configured = LogcoreConfig.minSeverity
        return Level.toLevel(configured, Level.ERROR)
    }

    fun formatEvent(event: ILoggingEvent): LogcoreEntry? {
        val timestamp = Instant.ofEpochMilli(event.timeStamp).toString()
        val severity = event.level.toString()
        val message = (event.formattedMessage ?: event.message ?: "").take(65536)
        val service = LogcoreConfig.service
        val env = LogcoreConfig.env

        val mdc = event.mdcPropertyMap
        val trace = extractTrace(mdc)
        val error = extractError(event.throwableProxy)
        val labels = extractLabels(event, mdc)
        val context = if (!mdc.isNullOrEmpty()) mdc else null

        val contextJson = if (!context.isNullOrEmpty()) {
            try {
                LogcoreTransport.objectMapper.writeValueAsString(context)
            } catch (e: Exception) {
                "{}"
            }
        } else {
            "{}"
        }

        val insertId = computeInsertId(timestamp, service, severity, message, contextJson)

        return LogcoreEntry(
            timestamp = timestamp,
            severity = severity,
            message = message,
            service = service,
            env = env,
            insertId = insertId,
            trace = trace,
            error = error,
            labels = labels,
            context = context
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
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun extractTrace(mdc: Map<String, String>?): LogcoreTrace? {
        if (mdc == null) return null
        val traceId = mdc["traceId"]
        val spanId = mdc["spanId"]
        val sampledStr = mdc["sampled"]
        if (!traceId.isNullOrEmpty() && !spanId.isNullOrEmpty()) {
            return LogcoreTrace(
                traceId = traceId,
                spanId = spanId,
                sampled = sampledStr?.toBooleanStrictOrNull() ?: true
            )
        }

        val traceparent = mdc["traceparent"]
        if (!traceparent.isNullOrEmpty()) {
            val parts = traceparent.split("-")
            if (parts.size >= 4 && parts[1].length == 32 && parts[2].length == 16) {
                val flags = parts[3].toIntOrNull(16) ?: 0
                return LogcoreTrace(
                    traceId = parts[1],
                    spanId = parts[2],
                    sampled = (flags and 1) == 1
                )
            }
        }
        return null
    }

    private fun extractError(throwableProxy: IThrowableProxy?): LogcoreError? {
        if (throwableProxy == null) return null

        val type = throwableProxy.className
        val message = throwableProxy.message ?: ""

        val chain = mutableListOf<IThrowableProxy>()
        var current: IThrowableProxy? = throwableProxy
        while (current != null && !chain.contains(current)) {
            chain.add(current)
            current = current.cause
        }

        val frames = mutableListOf<LogcoreStackFrame>()
        // Root cause first: chain.reversed()
        for (tp in chain.reversed()) {
            val stepArray = tp.stackTraceElementProxyArray ?: continue
            for (stepProxy in stepArray) {
                val step = stepProxy.stackTraceElement ?: continue
                if (frames.size >= 50) break
                frames.add(
                    LogcoreStackFrame(
                        function = "${step.className}.${step.methodName}",
                        file = step.fileName ?: "Unknown",
                        line = if (step.lineNumber > 0) step.lineNumber else 0,
                        column = 0,
                        inApp = isInApp(step.className)
                    )
                )
            }
            if (frames.size >= 50) break
        }

        return LogcoreError(
            type = type,
            message = message,
            stack = frames
        )
    }

    private fun isInApp(className: String): Boolean {
        if (className.startsWith("io.realworld.logcore.LogcoreAppender") ||
            className.startsWith("io.realworld.logcore.LogcoreTransport")
        ) return false
        if (className.startsWith("io.realworld.")) return true
        return false
    }

    private fun extractLabels(event: ILoggingEvent, mdc: Map<String, String>?): Map<String, String>? {
        val labels = mutableMapOf<String, String>()
        if (!event.loggerName.isNullOrEmpty()) {
            labels["logger"] = event.loggerName.take(1024)
        }
        if (!event.threadName.isNullOrEmpty()) {
            labels["thread"] = event.threadName.take(1024)
        }
        if (mdc != null) {
            for ((k, v) in mdc) {
                if (labels.size >= 32) break
                if (k != "traceId" && k != "spanId" && k != "sampled" && k != "traceparent") {
                    labels[k.take(128)] = v.take(1024)
                }
            }
        }
        return if (labels.isNotEmpty()) labels else null
    }

    companion object {
        const val APPENDER_NAME = "LOGCORE"

        fun ensureAttached() {
            try {
                val factory = LoggerFactory.getILoggerFactory()
                if (factory is LoggerContext) {
                    val rootLogger = factory.getLogger(Logger.ROOT_LOGGER_NAME)
                    if (rootLogger.getAppender(APPENDER_NAME) == null) {
                        val appender = LogcoreAppender()
                        appender.name = APPENDER_NAME
                        appender.context = factory
                        appender.start()
                        rootLogger.addAppender(appender)
                    }
                }
            } catch (t: Throwable) {
                // Ignore
            }
        }
    }
}
