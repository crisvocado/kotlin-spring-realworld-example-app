package io.realworld.logcore

import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

object LogcoreProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        var capturedEnvelopeJson: String? = null

        LogcoreHttpTransport.sendHandler = { _, _, body ->
            capturedEnvelopeJson = body
            true
        }

        try {
            LogcoreAppender.install()
            val logger = LoggerFactory.getLogger(LogcoreProbe::class.java)

            try {
                throw IllegalStateException("Logcore probe verification synthetic failure")
            } catch (ex: Exception) {
                logger.error("Logcore probe test error: ${ex.message}", ex)
            }

            LogcoreHttpTransport.flushSync()

            val output = capturedEnvelopeJson
            if (output.isNullOrBlank()) {
                System.err.println("[logcore-probe] Failed: No log entry was captured by the transport.")
                exitProcess(1)
            }

            println(output)
            exitProcess(0)
        } finally {
            LogcoreHttpTransport.sendHandler = null
            LogcoreHttpTransport.closeInstance()
        }
    }
}
