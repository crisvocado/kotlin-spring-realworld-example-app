package io.realworld.logcore

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import org.slf4j.LoggerFactory

class LogcoreAppender : AppenderBase<ILoggingEvent>() {

    companion object {
        const val APPENDER_NAME = "LOGCORE"

        @Synchronized
        fun attach(context: LoggerContext = LoggerFactory.getILoggerFactory() as LoggerContext) {
            val rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
            if (rootLogger.getAppender(APPENDER_NAME) == null) {
                val appender = LogcoreAppender()
                appender.name = APPENDER_NAME
                appender.context = context
                appender.start()
                rootLogger.addAppender(appender)
            }
        }

        @Synchronized
        fun detach(context: LoggerContext = LoggerFactory.getILoggerFactory() as LoggerContext) {
            val rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
            val existing = rootLogger.getAppender(APPENDER_NAME)
            if (existing != null) {
                existing.stop()
                rootLogger.detachAppender(existing)
            }
        }

        fun isAttached(context: LoggerContext = LoggerFactory.getILoggerFactory() as LoggerContext): Boolean {
            val rootLogger = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
            return rootLogger.getAppender(APPENDER_NAME) != null
        }
    }

    override fun append(eventObject: ILoggingEvent?) {
        if (eventObject == null || !isStarted) {
            return
        }

        val minLevel = Level.toLevel(LogcoreConfig.minSeverity, Level.ERROR)
        if (!eventObject.level.isGreaterOrEqual(minLevel)) {
            return
        }

        try {
            val entry = LogcoreEntryBuilder.fromLoggingEvent(eventObject)
            LogcoreTransport.enqueue(entry)
        } catch (e: Exception) {
            System.err.println("Logcore appender error: ${e.message}")
        }
    }
}
