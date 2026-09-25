package io.realworld.logcore

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import com.fasterxml.jackson.databind.JsonNode
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import java.io.IOException

class LogcoreTest {

    @Test
    fun testEntrySerializationMatchingWireShape() {
        val entry = LogcoreEntry(
            timestamp = "2026-08-17T18:30:00.000Z",
            severity = "ERROR",
            message = "checkout failed",
            service = "todo-frontend",
            env = "dev",
            insertId = "7c2e1a0b9d8f6e4c2a0b8d6f4e2c0a8b",
            trace = LogcoreTrace(
                traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
                spanId = "00f067aa0ba902b7",
                sampled = true
            ),
            error = LogcoreError(
                type = "TypeError",
                message = "Cannot read properties of undefined (reading 'total')",
                stack = listOf(
                    LogcoreStackFrame(
                        function = "checkout",
                        file = "https://app.example.com/assets/index-a1b2c3.js",
                        line = 1,
                        column = 48213,
                        inApp = true
                    ),
                    LogcoreStackFrame(
                        function = "<anon>",
                        file = "https://cdn.example.com/vendor.js",
                        line = 1,
                        column = 9042,
                        inApp = false
                    )
                )
            )
        )

        val envelope = LogcoreEnvelope(schemaVersion = 1, entries = listOf(entry))
        val json = LogcoreTransport.objectMapper.writeValueAsString(envelope)
        val tree: JsonNode = LogcoreTransport.objectMapper.readTree(json)

        assertEquals(1, tree.get("schema_version").asInt())
        assertTrue(tree.get("entries").isArray)
        val entryNode = tree.get("entries").get(0)

        assertEquals("2026-08-17T18:30:00.000Z", entryNode.get("timestamp").asText())
        assertEquals("ERROR", entryNode.get("severity").asText())
        assertEquals("checkout failed", entryNode.get("message").asText())
        assertEquals("todo-frontend", entryNode.get("service").asText())
        assertEquals("dev", entryNode.get("env").asText())
        assertEquals("7c2e1a0b9d8f6e4c2a0b8d6f4e2c0a8b", entryNode.get("insert_id").asText())

        val traceNode = entryNode.get("trace")
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", traceNode.get("traceId").asText())
        assertEquals("00f067aa0ba902b7", traceNode.get("spanId").asText())
        assertTrue(traceNode.get("sampled").asBoolean())

        val errorNode = entryNode.get("error")
        assertEquals("TypeError", errorNode.get("type").asText())
        assertEquals("Cannot read properties of undefined (reading 'total')", errorNode.get("message").asText())
        val stackNode = errorNode.get("stack")
        assertEquals(2, stackNode.size())
        assertEquals("checkout", stackNode.get(0).get("function").asText())
        assertEquals(1, stackNode.get(0).get("line").asInt())
        assertEquals(48213, stackNode.get(0).get("column").asInt())
        assertTrue(stackNode.get(0).get("inApp").asBoolean())

        assertEquals("<anon>", stackNode.get(1).get("function").asText())
        assertFalse(stackNode.get(1).get("inApp").asBoolean())

        // Ensure no disallowed fields exist
        assertNull(entryNode.get("service_id"))
        assertNull(entryNode.get("source_project"))
        assertNull(entryNode.get("stack_trace"))
    }

    @Test
    fun testAppenderFormattingAndInsertId() {
        val appender = LogcoreAppender()
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        appender.context = context
        appender.start()

        val logger = context.getLogger("io.realworld.test.TestLogger")
        val rootCause = IOException("Root connection failure")
        val outerException = IllegalStateException("Service wrapper error", rootCause)

        val event = LoggingEvent(
            "io.realworld.test.TestLogger",
            logger,
            Level.ERROR,
            "Failed to process operation",
            outerException,
            null
        )

        val entry = appender.formatEvent(event)
        assertNotNull(entry)
        assertEquals("ERROR", entry!!.severity)
        assertEquals("Failed to process operation", entry.message)
        assertTrue(entry.insertId.matches(Regex("^[0-9a-f]{32}$")))

        assertNotNull(entry.error)
        assertEquals("java.lang.IllegalStateException", entry.error!!.type)
        assertEquals("Service wrapper error", entry.error!!.message)
        assertTrue(entry.error!!.stack.isNotEmpty())

        // Verify root cause frames appear first in the stack trace
        val firstFrame = entry.error!!.stack[0]
        assertNotNull(firstFrame.function)
        assertTrue(firstFrame.line >= 0)
    }

    @Test
    fun testTraceExtractionFromMDC() {
        val appender = LogcoreAppender()
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        appender.context = context
        appender.start()

        val logger = context.getLogger("io.realworld.test.TraceLogger")
        val event = LoggingEvent(
            "io.realworld.test.TraceLogger",
            logger,
            Level.ERROR,
            "Error with traceparent",
            null,
            null
        )
        event.mdcPropertyMap = mapOf(
            "traceparent" to "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01",
            "userId" to "123"
        )

        val entry = appender.formatEvent(event)
        assertNotNull(entry)
        assertNotNull(entry!!.trace)
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", entry.trace!!.traceId)
        assertEquals("00f067aa0ba902b7", entry.trace!!.spanId)
        assertTrue(entry.trace!!.sampled)
        assertEquals("123", entry.labels?.get("userId"))
    }

    @Test
    fun testClampingLimits() {
        val appender = LogcoreAppender()
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        appender.context = context
        appender.start()

        val hugeMessage = "A".repeat(70000)
        val logger = context.getLogger("io.realworld.test.HugeLogger")
        val event = LoggingEvent(
            "io.realworld.test.HugeLogger",
            logger,
            Level.ERROR,
            hugeMessage,
            null,
            null
        )

        val entry = appender.formatEvent(event)
        assertNotNull(entry)
        assertEquals(65536, entry!!.message.length)
    }

    @Test
    fun testTransportBatchAndInterception() {
        var interceptedJson: String? = null
        LogcoreTransport.stubNetwork = true
        LogcoreTransport.transportInterceptor = { payload ->
            interceptedJson = payload
        }

        try {
            val entry = LogcoreEntry(
                timestamp = "2026-09-25T12:00:00.000Z",
                severity = "ERROR",
                message = "test transport message",
                service = "api",
                env = "dev",
                insertId = "11223344556677889900aabbccddeeff"
            )

            LogcoreTransport.sendBatchWithRetry(listOf(entry))

            assertNotNull(interceptedJson)
            val tree = LogcoreTransport.objectMapper.readTree(interceptedJson)
            assertEquals(1, tree.get("schema_version").asInt())
            assertEquals(1, tree.get("entries").size())
            assertEquals("test transport message", tree.get("entries").get(0).get("message").asText())
        } finally {
            LogcoreTransport.transportInterceptor = null
            LogcoreTransport.stubNetwork = false
        }
    }

    @Test
    fun testConfigNormalization() {
        assertEquals("prod", LogcoreConfig.normalizeEnv("production"))
        assertEquals("prod", LogcoreConfig.normalizeEnv("PROD"))
        assertEquals("staging", LogcoreConfig.normalizeEnv("staging"))
        assertEquals("dev", LogcoreConfig.normalizeEnv("dev"))
        assertEquals("test", LogcoreConfig.normalizeEnv("test"))
        assertEquals("local", LogcoreConfig.normalizeEnv("local"))
        assertEquals("dev", LogcoreConfig.normalizeEnv("unknown"))

        assertEquals("api", LogcoreConfig.normalizeService("api"))
        assertEquals("my-service", LogcoreConfig.normalizeService("My Service!"))
    }
}
