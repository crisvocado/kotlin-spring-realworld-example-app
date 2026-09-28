package io.realworld.logcore

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.slf4j.LoggerFactory

object LogcoreProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        val mapper = ObjectMapper().registerKotlinModule()
        var emittedEntryJson: String? = null

        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val rootLogger = context.getLogger(Logger.ROOT_LOGGER_NAME)
        rootLogger.detachAndStopAllAppenders()

        LogcoreAppender.onEntryEmitted = { entry ->
            emittedEntryJson = mapper.writeValueAsString(entry)
        }

        LogcoreAppender.install(context)
        val logger = LoggerFactory.getLogger(LogcoreProbe::class.java)
        logger.error("logcore probe test error", RuntimeException("logcore probe exception"))

        LogcoreHttpTransport.closeInstance()

        if (emittedEntryJson != null) {
            println(emittedEntryJson)
        }
    }
}
