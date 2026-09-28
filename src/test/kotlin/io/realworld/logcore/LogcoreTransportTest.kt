package io.realworld.logcore

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.LoggingEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse

class LogcoreTransportTest {

    @BeforeEach
    fun resetTransport() {
        LogcoreTransport.customSender = null
        LogcoreTransport.lastEmittedEnvelope = null
    }

    @Test
    fun testClamping() {
        val longMessage = "a".repeat(70000)
        val entry = LogcoreEntryBuilder.fromThrowable(
            message = longMessage,
            throwable = RuntimeException("Clamp test"),
            severity = "ERROR"
        )
        assertEquals(65536, entry.message.length)
        assertTrue(entry.error!!.stack.size <= 50)
    }

    @Test
    fun testDeterministicInsertId() {
        val id1 = LogcoreEntryBuilder.computeInsertId(
            "2026-08-17T18:30:00.000Z",
            "api",
            "ERROR",
            "test message",
            "{}"
        )
        val id2 = LogcoreEntryBuilder.computeInsertId(
            "2026-08-17T18:30:00.000Z",
            "api",
            "ERROR",
            "test message",
            "{}"
        )
        assertEquals(id1, id2)
        assertEquals(32, id1.length)
        assertTrue(id1.matches(Regex("^[0-9a-f]{32}$")))
    }

    @Test
    fun testTraceFilterWithHeader() {
        val filter = LogcoreTraceFilter()
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        val traceId = "4bf92f3577b34da6a3ce929d0e0e4736"
        val spanId = "00f067aa0ba902b7"
        request.addHeader("traceparent", "00-$traceId-$spanId-01")

        filter.doFilter(request, response, chain)

        val outgoingTraceparent = response.getHeader("traceparent")
        assertNotNull(outgoingTraceparent)
        assertTrue(outgoingTraceparent!!.startsWith("00-$traceId-$spanId-01"))
    }

    @Test
    fun testTraceFilterWithoutHeader() {
        val filter = LogcoreTraceFilter()
        val request = MockHttpServletRequest()
        val response = MockHttpServletResponse()
        val chain = MockFilterChain()

        filter.doFilter(request, response, chain)

        val outgoingTraceparent = response.getHeader("traceparent")
        assertNotNull(outgoingTraceparent)
        val parsed = LogcoreEntryBuilder.parseTraceparent(outgoingTraceparent!!)
        assertNotNull(parsed)
        assertEquals(32, parsed!!.traceId.length)
        assertEquals(16, parsed.spanId.length)
        assertTrue(parsed.sampled)
    }

    @Test
    fun testConfigDefaultsAndNormalization() {
        assertEquals("api", LogcoreConfig.service)
        assertEquals("dev", LogcoreConfig.env)
        assertEquals("ERROR", LogcoreConfig.minSeverity)
        assertTrue(LogcoreConfig.enabled)
        assertTrue(LogcoreConfig.ingestUrl.endsWith("/v1/logs"))
    }

    @Test
    fun testAppenderIgnoresInfoAndCapturesError() {
        val captured = mutableListOf<LogcoreEnvelope>()
        LogcoreTransport.customSender = { envelope ->
            captured.add(envelope)
            true
        }

        val appender = LogcoreAppender()
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        appender.context = context
        appender.start()

        val rootLogger = context.getLogger("test.logger")

        val infoEvent = LoggingEvent(
            "test.logger",
            rootLogger,
            Level.INFO,
            "Info message should be ignored",
            null,
            null
        )
        appender.doAppend(infoEvent)
        LogcoreTransport.flush()
        assertTrue(captured.isEmpty(), "INFO event should not be forwarded")

        val errorEvent = LoggingEvent(
            "test.logger",
            rootLogger,
            Level.ERROR,
            "Error message should be forwarded",
            null,
            null
        )
        appender.doAppend(errorEvent)
        LogcoreTransport.flush()
        assertEquals(1, captured.size, "ERROR event should be forwarded")
        assertEquals("Error message should be forwarded", captured[0].entries[0].message)

        appender.stop()
    }
}
