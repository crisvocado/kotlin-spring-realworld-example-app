package io.realworld.logcore

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

object LogcoreProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val capturedEnvelope = AtomicReference<String?>(null)

        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME)
        rootLogger.detachAndStopAllAppenders()

        LogcoreHttpTransport.onSendBatch = { payload ->
            capturedEnvelope.set(payload)
        }

        LogcoreAppender.install(context)
        val logger = LoggerFactory.getLogger(LogcoreProbe::class.java)
        logger.error("logcore probe test error", RuntimeException("logcore probe exception"))

        LogcoreHttpTransport.flushSync()
        LogcoreHttpTransport.closeInstance()

        val payload = capturedEnvelope.get()
        if (payload != null) {
            println(payload)
        } else {
            System.err.println("[logcore] Probe failed: No payload was captured")
            System.exit(1)
        }
    }
}
