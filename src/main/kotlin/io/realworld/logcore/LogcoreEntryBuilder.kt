package io.realworld.logcore

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.IThrowableProxy
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.MDC
import java.security.MessageDigest
import java.time.Instant

object LogcoreEntryBuilder {

    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    fun fromLoggingEvent(event: ILoggingEvent): LogcoreEntry {
        val timestamp = Instant.ofEpochMilli(event.timeStamp).toString()
        val severity = event.level?.levelStr ?: "ERROR"
        val rawMessage = event.formattedMessage ?: event.message ?: ""
        val message = if (rawMessage.length > 65536) rawMessage.substring(0, 65536) else rawMessage
        val service = LogcoreConfig.service
        val env = LogcoreConfig.env

        val contextMap = mutableMapOf<String, Any?>()
        val contextJson = if (contextMap.isNotEmpty()) {
            try {
                objectMapper.writeValueAsString(contextMap)
            } catch (_: Exception) {
                "{}"
            }
        } else {
            "{}"
        }

        val insertId = computeInsertId(timestamp, service, severity, message, contextJson)
        val trace = extractTraceFromMdc()
        val error = extractErrorFromThrowableProxy(event.throwableProxy)
        val labels = extractLabels(event)

        return LogcoreEntry(
            timestamp = timestamp,
            severity = severity,
            message = message,
            service = service,
            env = env,
            insertId = insertId,
            trace = trace,
            error = error,
            labels = labels.ifEmpty { null },
            context = contextMap.ifEmpty { null }
        )
    }

    fun fromThrowable(
        message: String,
        throwable: Throwable?,
        severity: String = "ERROR",
        timestamp: String = Instant.now().toString(),
        customLabels: Map<String, String>? = null,
        customContext: Map<String, Any?>? = null
    ): LogcoreEntry {
        val clampedMessage = if (message.length > 65536) message.substring(0, 65536) else message
        val service = LogcoreConfig.service
        val env = LogcoreConfig.env
        val contextJson = if (customContext != null && customContext.isNotEmpty()) {
            try {
                objectMapper.writeValueAsString(customContext)
            } catch (_: Exception) {
                "{}"
            }
        } else {
            "{}"
        }

        val insertId = computeInsertId(timestamp, service, severity, clampedMessage, contextJson)
        val trace = extractTraceFromMdc()
        val error = throwable?.let { extractErrorFromThrowable(it) }

        val labels = mutableMapOf<String, String>()
        customLabels?.forEach { (k, v) ->
            labels[k.take(1024)] = v.take(1024)
        }

        return LogcoreEntry(
            timestamp = timestamp,
            severity = severity,
            message = clampedMessage,
            service = service,
            env = env,
            insertId = insertId,
            trace = trace,
            error = error,
            labels = labels.takeIf { it.isNotEmpty() }?.entries?.take(32)?.associate { it.key to it.value },
            context = customContext?.ifEmpty { null }
        )
    }

    fun computeInsertId(
        timestamp: String,
        service: String,
        severity: String,
        message: String,
        contextJson: String
    ): String {
        val input = "$timestamp|$service|$severity|$message|$contextJson"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        return hex.substring(0, 32)
    }

    private fun extractTraceFromMdc(): LogcoreTrace? {
        val mdcTraceparent = MDC.get("traceparent")
        if (!mdcTraceparent.isNullOrBlank()) {
            val parsed = parseTraceparent(mdcTraceparent)
            if (parsed != null) {
                return parsed
            }
        }

        val traceId = MDC.get("traceId") ?: MDC.get("trace_id") ?: MDC.get("X-B3-TraceId")
        val spanId = MDC.get("spanId") ?: MDC.get("span_id") ?: MDC.get("X-B3-SpanId")
        val sampledStr = MDC.get("traceSampled") ?: MDC.get("sampled")

        if (!traceId.isNullOrBlank() && traceId.matches(Regex("^[0-9a-fA-F]{16,32}$"))) {
            val normalizedTraceId = traceId.padStart(32, '0').lowercase()
            val normalizedSpanId = (spanId ?: "0000000000000000").padStart(16, '0').takeLast(16).lowercase()
            val sampled = sampledStr?.toBooleanStrictOrNull() ?: true
            return LogcoreTrace(
                traceId = normalizedTraceId,
                spanId = normalizedSpanId,
                sampled = sampled
            )
        }

        return null
    }

    fun parseTraceparent(header: String): LogcoreTrace? {
        val parts = header.trim().split("-")
        if (parts.size >= 4) {
            val traceId = parts[1]
            val spanId = parts[2]
            val flags = parts[3]
            if (traceId.matches(Regex("^[0-9a-fA-F]{32}$")) && spanId.matches(Regex("^[0-9a-fA-F]{16}$"))) {
                val flagsInt = flags.toIntOrNull(16) ?: 0
                val sampled = (flagsInt and 1) == 1
                return LogcoreTrace(
                    traceId = traceId.lowercase(),
                    spanId = spanId.lowercase(),
                    sampled = sampled
                )
            }
        }
        return null
    }

    private fun extractErrorFromThrowableProxy(proxy: IThrowableProxy?): LogcoreError? {
        if (proxy == null) return null

        var rootProxy: IThrowableProxy = proxy
        var current: IThrowableProxy? = proxy
        while (current != null) {
            val stepArray = current.stackTraceElementProxyArray
            if (stepArray != null && stepArray.isNotEmpty()) {
                rootProxy = current
            }
            current = current.cause
        }

        val fullClassName = proxy.className ?: "UnknownException"
        val type = fullClassName.substringAfterLast('.').ifEmpty { fullClassName }
        val message = proxy.message ?: ""

        val frames = mutableListOf<LogcoreStackFrame>()
        val stepArray = rootProxy.stackTraceElementProxyArray
        if (stepArray != null) {
            for (step in stepArray) {
                val ste = step.stackTraceElement
                if (ste != null) {
                    val className = ste.className ?: ""
                    val fileName = ste.fileName ?: (className.substringAfterLast('.') + ".kt")
                    val line = if (ste.lineNumber >= 0) ste.lineNumber else 0
                    val inApp = className.startsWith("io.realworld")
                    frames.add(
                        LogcoreStackFrame(
                            function = ste.methodName ?: "unknown",
                            file = fileName,
                            line = line,
                            column = 0,
                            inApp = inApp
                        )
                    )
                }
            }
        }

        return LogcoreError(
            type = type,
            message = message,
            stack = frames.take(50)
        )
    }

    private fun extractErrorFromThrowable(throwable: Throwable): LogcoreError {
        var root: Throwable = throwable
        var current: Throwable? = throwable
        while (current != null) {
            if (current.stackTrace != null && current.stackTrace.isNotEmpty()) {
                root = current
            }
            current = current.cause
        }

        val type = throwable.javaClass.simpleName.ifEmpty { throwable.javaClass.name }
        val message = throwable.message ?: ""

        val frames = root.stackTrace.map { ste ->
            val className = ste.className ?: ""
            val fileName = ste.fileName ?: (className.substringAfterLast('.') + ".kt")
            val line = if (ste.lineNumber >= 0) ste.lineNumber else 0
            val inApp = className.startsWith("io.realworld")
            LogcoreStackFrame(
                function = ste.methodName ?: "unknown",
                file = fileName,
                line = line,
                column = 0,
                inApp = inApp
            )
        }.take(50)

        return LogcoreError(
            type = type,
            message = message,
            stack = frames
        )
    }

    private fun extractLabels(event: ILoggingEvent): Map<String, String> {
        val labels = mutableMapOf<String, String>()
        event.loggerName?.let { labels["logger"] = it.take(1024) }
        event.threadName?.let { labels["thread"] = it.take(1024) }

        val mdc = event.mdcPropertyMap
        if (mdc != null) {
            for ((key, value) in mdc) {
                if (key !in listOf("traceId", "spanId", "traceSampled", "traceparent", "trace_id", "span_id") && value != null) {
                    labels[key.take(1024)] = value.take(1024)
                }
            }
        }

        return labels.entries.take(32).associate { it.key to it.value }
    }
}
