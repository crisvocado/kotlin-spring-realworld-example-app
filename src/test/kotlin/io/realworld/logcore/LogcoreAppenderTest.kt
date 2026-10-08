package io.realworld.logcore

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC

class LogcoreAppenderTest {

    private val mapper = ObjectMapper().registerKotlinModule()

    @Test
    fun testAppenderBuildsCorrectEntryStructureWithError() {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val logger = context.getLogger("io.realworld.test.TestLogger")
        val appender = LogcoreAppender()
        appender.context = context

        val rootCause = IllegalArgumentException("root cause message")
        val exception = RuntimeException("something went wrong", rootCause)
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
        assertTrue(entry.timestamp.endsWith("Z"))
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
        assertNotNull(firstFrame.line)
        assertTrue(firstFrame.line!! > 0)
        assertNull(firstFrame.column)

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

    @Test
    fun testAppenderWithoutErrorProducesNullError() {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val logger = context.getLogger("io.realworld.test.TestLogger")
        val appender = LogcoreAppender()
        appender.context = context

        val event = LoggingEvent(
            "io.realworld.test.TestLogger",
            logger,
            Level.ERROR,
            "simple error without exception",
            null,
            null
        )

        val entry = appender.buildEntry(event)
        assertNull(entry.error)
        assertEquals("ERROR", entry.severity)
        assertEquals("simple error without exception", entry.message)
    }

    @Test
    fun testTraceInterceptorParsing() {
        val interceptor = LogcoreTraceInterceptor()

        // Valid W3C traceparent
        val valid = interceptor.parseTraceparent("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")
        assertNotNull(valid)
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", valid?.first)
        assertEquals("00f067aa0ba902b7", valid?.second)
        assertEquals(true, valid?.third)

        // Invalid or null
        assertNull(interceptor.parseTraceparent(null))
        assertNull(interceptor.parseTraceparent(""))
        assertNull(interceptor.parseTraceparent("invalid-header"))
        assertNull(interceptor.parseTraceparent("00-00000000000000000000000000000000-0000000000000000-01"))
    }

    @Test
    fun testTransportCapturesThroughSendHandler() {
        var capturedJson: String? = null
        LogcoreHttpTransport.sendHandler = { _, _, body ->
            capturedJson = body
            true
        }

        try {
            val config = LogcoreConfig(
                url = "https://logcore.example.com",
                apiKey = "test-api-key",
                enabled = true,
                service = "api",
                env = "test",
                minSeverity = "ERROR"
            )
            val transport = LogcoreHttpTransport(config)
            val entry = LogcoreEntry(
                timestamp = "2026-08-12T18:30:00.000Z",
                severity = "ERROR",
                message = "transport test",
                service = "api",
                env = "test",
                insertId = "0123456789abcdef0123456789abcdef"
            )

            transport.enqueue(entry)
            transport.flushBatch(true)

            assertNotNull(capturedJson)
            val tree = mapper.readTree(capturedJson)
            assertEquals(1, tree.get("schema_version").asInt())
            assertEquals(1, tree.get("entries").size())
            assertEquals("transport test", tree.get("entries").get(0).get("message").asText())
        } finally {
            LogcoreHttpTransport.sendHandler = null
        }
    }
}
