package io.realworld.logcore

import com.fasterxml.jackson.annotation.JsonInclude
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

class LogcoreEmitterTest {

    private val objectMapper = ObjectMapper()
        .registerKotlinModule()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)

    @Test
    fun testLogcoreEmitsErrorEntryMatchingContract() {
        val capturedPayloads = mutableListOf<String>()

        val testConfig = LogcoreConfig(
            enabled = true,
            url = "https://logcore-dev-352942961463.us-east4.run.app",
            apiKey = "test-key",
            service = "kotlin-spring-realworld-example-app",
            env = "dev",
            minSeverity = "ERROR",
            batchSize = 1,
            flushIntervalMs = 60000L
        )

        val testClient = LogcoreClient(config = testConfig)
        testClient.customSender = { json ->
            capturedPayloads.add(json)
            true
        }

        // Install appender
        val appender = LogcoreAppender.install(testClient)
        assertNotNull(appender, "LogcoreAppender should be installed successfully")

        val logger = LoggerFactory.getLogger(LogcoreEmitterTest::class.java)

        // Set trace in MDC
        MDC.put(LogcoreTraceInterceptor.MDC_TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736")
        MDC.put(LogcoreTraceInterceptor.MDC_SPAN_ID, "00f067aa0ba902b7")
        MDC.put(LogcoreTraceInterceptor.MDC_SAMPLED, "true")

        try {
            // Non-error logs should not be emitted
            logger.info("This is an info message and should be ignored")

            // Emit an ERROR log with exception
            val exception = IllegalStateException("Cannot read properties of undefined (reading 'total')")
            logger.error("checkout failed", exception)

            // Flush client
            testClient.flush()
        } finally {
            MDC.clear()
            testClient.close()
            LogcoreAppender.uninstall()
        }

        assertTrue(capturedPayloads.isNotEmpty(), "At least one payload should have been flushed")
        val emittedJson = capturedPayloads.first()

        // Parse and validate payload
        val payloadNode = objectMapper.readTree(emittedJson)
        assertEquals(1, payloadNode.get("schema_version").asInt())
        assertTrue(payloadNode.has("entries"))
        val entriesNode = payloadNode.get("entries")
        assertEquals(1, entriesNode.size())

        val entryNode = entriesNode.get(0)
        val entryJson = objectMapper.writeValueAsString(entryNode)
        println(entryJson)
        assertEquals("ERROR", entryNode.get("severity").asText())
        assertEquals("checkout failed", entryNode.get("message").asText())
        assertEquals("kotlin-spring-realworld-example-app", entryNode.get("service").asText())
        assertEquals("dev", entryNode.get("env").asText())

        // Validate insert_id format (32 hex characters)
        val insertId = entryNode.get("insert_id").asText()
        assertNotNull(insertId)
        assertTrue(insertId.matches(Regex("^[0-9a-f]{32}$")), "insert_id must be 32 hex chars: $insertId")

        // Validate trace
        assertTrue(entryNode.has("trace"))
        val traceNode = entryNode.get("trace")
        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", traceNode.get("traceId").asText())
        assertEquals("00f067aa0ba902b7", traceNode.get("spanId").asText())
        assertTrue(traceNode.get("sampled").asBoolean())

        // Validate error
        assertTrue(entryNode.has("error"))
        val errorNode = entryNode.get("error")
        assertEquals("IllegalStateException", errorNode.get("type").asText())
        assertEquals("Cannot read properties of undefined (reading 'total')", errorNode.get("message").asText())
        assertTrue(errorNode.has("stack"))
        val stackNode = errorNode.get("stack")
        assertTrue(stackNode.size() > 0)

        // Validate top frame (innermost first)
        val topFrame = stackNode.get(0)
        assertTrue(topFrame.has("function"))
        assertTrue(topFrame.has("file"))
        assertTrue(topFrame.has("line"))
        assertTrue(topFrame.has("column"))
        assertTrue(topFrame.has("inApp"))
        assertTrue(topFrame.get("inApp").asBoolean(), "First frame should be inApp=true")

        // Validate no illegal GCP fields present for HTTP external backend
        assertFalse(entryNode.has("source_project"))
        assertFalse(entryNode.has("service_id"))
    }

    @Test
    fun testInsertIdIsDeterministic() {
        val id1 = LogcoreConfig.computeInsertId(
            timestamp = "2026-08-17T18:30:00.000Z",
            service = "api",
            severity = "ERROR",
            message = "test message",
            contextJson = ""
        )
        val id2 = LogcoreConfig.computeInsertId(
            timestamp = "2026-08-17T18:30:00.000Z",
            service = "api",
            severity = "ERROR",
            message = "test message",
            contextJson = ""
        )
        assertEquals(id1, id2)
        assertEquals(32, id1.length)
        assertTrue(id1.matches(Regex("^[0-9a-f]{32}$")))
    }
}
