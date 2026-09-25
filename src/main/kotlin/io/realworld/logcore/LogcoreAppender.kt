package io.realworld.logcore

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Instant

class LogcoreAppender(
    private val client: LogcoreClient = LogcoreClient.instance,
    val config: LogcoreConfig = client.config
) : AppenderBase<ILoggingEvent>() {

    private val minLevel: Level = Level.toLevel(config.minSeverity, Level.ERROR)

    override fun append(event: ILoggingEvent?) {
        if (event == null || !isStarted) return
        if (!event.level.isGreaterOrEqual(minLevel)) return

        try {
            val timestamp = Instant.ofEpochMilli(event.timeStamp).toString()
            val severity = event.level.toString()
            val message = (event.formattedMessage ?: event.message ?: "").take(65536)

            val mdc = event.mdcPropertyMap ?: emptyMap()
            val traceId = mdc[LogcoreTraceInterceptor.MDC_TRACE_ID] ?: mdc["traceId"] ?: mdc["trace_id"]
            val spanId = mdc[LogcoreTraceInterceptor.MDC_SPAN_ID] ?: mdc["spanId"] ?: mdc["span_id"]
            val sampled = (mdc[LogcoreTraceInterceptor.MDC_SAMPLED] ?: mdc["sampled"])?.toBooleanStrictOrNull()

            val trace = if (!traceId.isNullOrBlank()) {
                LogcoreTrace(
                    traceId = traceId,
                    spanId = spanId?.takeIf { it.isNotBlank() },
                    sampled = sampled
                )
            } else null

            val labelsMap = mutableMapOf<String, String>()
            if (!event.loggerName.isNullOrBlank()) {
                labelsMap["logger"] = event.loggerName.take(1024)
            }
            for ((k, v) in mdc) {
                if (labelsMap.size >= 32) break
                if (k !in TRACE_KEYS && v != null) {
                    labelsMap[k.take(1024)] = v.take(1024)
                }
            }
            val labels = if (labelsMap.isNotEmpty()) labelsMap else null

            var error: LogcoreError? = null
            if (event.throwableProxy != null) {
                val topProxy = event.throwableProxy
                val topType = if (topProxy.className.contains('.')) topProxy.className.substringAfterLast('.') else topProxy.className
                val topMessage = topProxy.message ?: topProxy.className

                var rootProxy = topProxy
                while (rootProxy.cause != null) {
                    rootProxy = rootProxy.cause
                }

                val stepArray = rootProxy.stackTraceElementProxyArray ?: topProxy.stackTraceElementProxyArray ?: emptyArray()
                val frames = stepArray.take(50).map { step ->
                    val ste = step.stackTraceElement
                    val fn = if (ste != null) "${ste.className}.${ste.methodName}" else "unknown"
                    val file = ste?.fileName ?: "Unknown"
                    val line = if (ste != null && ste.lineNumber > 0) ste.lineNumber else 0
                    val inApp = ste?.className?.startsWith("io.realworld") ?: false
                    LogcoreStackFrame(
                        function = fn,
                        file = file,
                        line = line,
                        column = 0,
                        inApp = inApp
                    )
                }
                error = LogcoreError(
                    type = topType,
                    message = topMessage,
                    stack = frames
                )
            }

            val insertId = LogcoreConfig.computeInsertId(timestamp, config.service, severity, message)

            val entry = LogcoreEntry(
                timestamp = timestamp,
                severity = severity,
                message = message,
                service = config.service,
                env = config.env,
                insertId = insertId,
                trace = trace,
                error = error,
                labels = labels
            )

            client.enqueue(entry)
        } catch (_: Exception) {
            // Never let logging failure propagate or break application
        }
    }

    companion object {
        const val APPENDER_NAME = "LOGCORE"
        private val TRACE_KEYS = setOf(
            LogcoreTraceInterceptor.MDC_TRACE_ID,
            LogcoreTraceInterceptor.MDC_SPAN_ID,
            LogcoreTraceInterceptor.MDC_SAMPLED,
            "traceId",
            "spanId",
            "trace_id",
            "span_id",
            "sampled"
        )

        @Synchronized
        fun install(client: LogcoreClient = LogcoreClient.instance): LogcoreAppender? {
            return try {
                val loggerContext = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return null
                val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
                val existing = rootLogger.getAppender(APPENDER_NAME)
                if (existing is LogcoreAppender) {
                    return existing
                }

                val appender = LogcoreAppender(client)
                appender.name = APPENDER_NAME
                appender.context = loggerContext
                appender.start()
                rootLogger.addAppender(appender)
                appender
            } catch (e: Exception) {
                System.err.println("Logcore failed to install appender: ${e.message}")
                null
            }
        }

        @Synchronized
        fun uninstall() {
            try {
                val loggerContext = LoggerFactory.getILoggerFactory() as? LoggerContext ?: return
                val rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME)
                val appender = rootLogger.getAppender(APPENDER_NAME)
                if (appender != null) {
                    rootLogger.detachAppender(appender)
                    appender.stop()
                }
            } catch (_: Exception) {
            }
        }
    }
}
