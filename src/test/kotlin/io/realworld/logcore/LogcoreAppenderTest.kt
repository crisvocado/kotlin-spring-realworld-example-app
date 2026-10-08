package io.realworld.logcore

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

class LogcoreAppenderTest {

    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun testAppenderBuildsCorrectEntryStructure() {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val logger = context.getLogger("io.realworld.test.TestLogger")
        val appender = LogcoreAppender()
        appender.context = context

        val exception = RuntimeException("something went wrong", IllegalArgumentException("root cause"))
        val event = LoggingEvent(
            "io.realworld.test.TestLogger",
            logger,
            Level.ERROR,
            "test error message",
            exception,
            null
        )

        val entry = appender.buildEntry(event)

        assertEquals("ERROR", entry.severity)
        assertEquals("test error message", entry.message)
        assertNotNull(entry.timestamp)
        assertNotNull(entry.insertId)
        assertEquals(32, entry.insertId.length)
        assertTrue(entry.insertId.matches(Regex("^[0-9a-f]{32}$")))

        // Error check
        assertNotNull(entry.error)
        assertEquals("java.lang.RuntimeException", entry.error?.type)
        assertEquals("something went wrong", entry.error?.message)
        assertTrue(entry.error!!.stack.isNotEmpty())

        val firstFrame = entry.error!!.stack.first()
        assertNotNull(firstFrame.function)
        assertNotNull(firstFrame.file)
        assertTrue(firstFrame.line != null && firstFrame.line!! > 0)
        assertEquals(null, firstFrame.column)

        // JSON Serialization check
        val envelope = LogcoreEnvelope(schemaVersion = 1, entries = listOf(entry))
        val json = mapper.writeValueAsString(envelope)
        val tree = mapper.readTree(json)

        assertEquals(1, tree.get("schema_version").asInt())
        assertTrue(tree.get("entries").isArray)
        val entryJson = tree.get("entries").get(0)
        assertEquals("ERROR", entryJson.get("severity").asText())
        assertEquals("test error message", entryJson.get("message").asText())
        assertTrue(entryJson.has("insert_id"))
        assertTrue(entryJson.has("service"))
        assertTrue(entryJson.has("env"))
        assertFalse(entryJson.has("source_project")) // absent
        assertFalse(entryJson.has("service_id")) // absent
    }
}
