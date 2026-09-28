package io.realworld.logcore

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC

class LogcoreEmitterTest {

    private val logger = LoggerFactory.getLogger(LogcoreEmitterTest::class.java)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    @BeforeEach
    fun setUp() {
        LogcoreAppender.attach()
    }

    @Test
    fun emitError() {
        var capturedEnvelope: LogcoreEnvelope? = null
        LogcoreTransport.customSender = { envelope ->
            capturedEnvelope = envelope
            true
        }

        val testTraceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val testSpanId = "00f067aa0ba902b7"
        MDC.put("traceparent", "00-$testTraceId-$testSpanId-01")
        MDC.put("custom_label", "emitter_test")

        try {
            val rootCause = IllegalArgumentException("Root failure details")
            val wrappedException = RuntimeException("Wrapped service error", rootCause)
            logger.error("Logcore emitter test error message", wrappedException)

            LogcoreTransport.flush()

            assertNotNull(capturedEnvelope, "Expected LogcoreTransport to capture an envelope")
            val envelope = capturedEnvelope!!
            assertEquals(1, envelope.schemaVersion)
            assertTrue(envelope.entries.isNotEmpty())

            val entry = envelope.entries.first()
            assertEquals("ERROR", entry.severity)
            assertEquals("Logcore emitter test error message", entry.message)
            assertEquals("api", entry.service)
            assertEquals("dev", entry.env)
            assertNotNull(entry.insertId)
            assertTrue(entry.insertId.matches(Regex("^[0-9a-f]{32}$")), "insert_id must be 32 hex chars")

            assertNotNull(entry.trace)
            assertEquals(testTraceId, entry.trace?.traceId)
            assertEquals(testSpanId, entry.trace?.spanId)
            assertEquals(true, entry.trace?.sampled)

            assertNotNull(entry.error)
            assertEquals("RuntimeException", entry.error?.type)
            assertEquals("Wrapped service error", entry.error?.message)
            assertTrue(entry.error!!.stack.isNotEmpty())

            val topFrame = entry.error!!.stack.first()
            assertEquals("emitError", topFrame.function)
            assertTrue(topFrame.inApp)
            assertEquals(0, topFrame.column)

            val serialized = objectMapper.writeValueAsString(entry)
            assertNotNull(serialized)
            println(serialized)

        } finally {
            MDC.clear()
            LogcoreTransport.customSender = null
        }
    }
}
