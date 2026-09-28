package io.realworld.logcore

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.io.Closeable
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object LogcoreTransport : Closeable {

    private const val MAX_BUFFER_CAPACITY = 1000
    private const val BATCH_SIZE_THRESHOLD = 20
    private const val MAX_ENTRIES_PER_REQUEST = 200
    private const val FLUSH_INTERVAL_SECONDS = 5L
    private const val MAX_CONSECUTIVE_FAILURES = 10
    private const val MAX_RETRIES = 3

    private val queue = ConcurrentLinkedDeque<LogcoreEntry>()
    private val queueSize = AtomicInteger(0)
    private val consecutiveFailures = AtomicInteger(0)
    private val isClosed = AtomicBoolean(false)
    private val objectMapper: ObjectMapper = jacksonObjectMapper()

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()

    // Test hook / stub sender for offline verification
    @Volatile
    var customSender: ((LogcoreEnvelope) -> Boolean)? = null

    @Volatile
    var lastEmittedEnvelope: LogcoreEnvelope? = null

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "logcore-transport-worker").apply {
            isDaemon = true
        }
    }

    init {
        scheduler.scheduleWithFixedDelay(
            { flushQueue(false) },
            FLUSH_INTERVAL_SECONDS,
            FLUSH_INTERVAL_SECONDS,
            TimeUnit.SECONDS
        )

        try {
            Runtime.getRuntime().addShutdownHook(Thread({
                flush()
                close()
            }, "logcore-shutdown-hook"))
        } catch (_: Exception) {
            // JVM is already shutting down
        }
    }

    val ingestUrl: String
        get() = LogcoreConfig.ingestUrl

    fun enqueue(entry: LogcoreEntry) {
        if (isClosed.get() || !LogcoreConfig.enabled) {
            return
        }

        // Circuit breaker: after 10 consecutive failed flushes, stop buffering and clear
        if (consecutiveFailures.get() >= MAX_CONSECUTIVE_FAILURES) {
            queue.clear()
            queueSize.set(0)
            return
        }

        // Buffer cap: drop oldest if buffer is full
        while (queueSize.get() >= MAX_BUFFER_CAPACITY) {
            val dropped = queue.pollFirst()
            if (dropped != null) {
                queueSize.decrementAndGet()
            } else {
                break
            }
        }

        queue.addLast(entry)
        val currentSize = queueSize.incrementAndGet()

        // Immediate flush trigger if batch size threshold is reached
        if (currentSize >= BATCH_SIZE_THRESHOLD) {
            scheduler.execute { flushQueue(false) }
        }
    }

    fun flush() {
        flushQueue(true)
    }

    private fun flushQueue(flushAll: Boolean) {
        if (consecutiveFailures.get() >= MAX_CONSECUTIVE_FAILURES) {
            queue.clear()
            queueSize.set(0)
            return
        }

        while (true) {
            val batch = mutableListOf<LogcoreEntry>()
            while (batch.size < MAX_ENTRIES_PER_REQUEST) {
                val item = queue.pollFirst() ?: break
                queueSize.decrementAndGet()
                batch.add(item)
            }

            if (batch.isEmpty()) {
                break
            }

            val envelope = LogcoreEnvelope(
                schemaVersion = 1,
                entries = batch
            )
            lastEmittedEnvelope = envelope

            val success = sendEnvelopeWithRetry(envelope)
            if (!success) {
                val failures = consecutiveFailures.incrementAndGet()
                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    System.err.println("Logcore circuit breaker tripped after $failures consecutive failures. Clearing buffer.")
                    queue.clear()
                    queueSize.set(0)
                }
                break
            } else {
                consecutiveFailures.set(0)
            }

            if (!flushAll && queueSize.get() < BATCH_SIZE_THRESHOLD) {
                break
            }
        }
    }

    private fun sendEnvelopeWithRetry(envelope: LogcoreEnvelope): Boolean {
        // If a test hook / custom sender is configured, delegate directly
        customSender?.let { sender ->
            return try {
                sender(envelope)
            } catch (e: Exception) {
                System.err.println("Logcore custom sender error: ${e.message}")
                false
            }
        }

        val apiKey = LogcoreConfig.apiKey
        if (apiKey.isNullOrBlank()) {
            // Without an API key, we drop gracefully but record the envelope
            return true
        }

        val jsonBody: String = try {
            objectMapper.writeValueAsString(envelope)
        } catch (e: Exception) {
            System.err.println("Logcore serialization error: ${e.message}")
            return false
        }

        var attempt = 0
        while (attempt <= MAX_RETRIES) {
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(ingestUrl))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, Charsets.UTF_8))
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
                val statusCode = response.statusCode()

                if (statusCode in 200..299) {
                    return true
                }

                // Retry on 429 or 5xx
                if (statusCode == 429 || statusCode in 500..599) {
                    if (attempt < MAX_RETRIES) {
                        val backoffMs = (1L shl attempt) * 250L
                        try {
                            Thread.sleep(backoffMs)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            return false
                        }
                        attempt++
                        continue
                    } else {
                        System.err.println("Logcore HTTP delivery failed after $MAX_RETRIES retries (HTTP $statusCode): ${response.body()}")
                        return false
                    }
                }

                // Permanent 4xx failure (e.g. 400, 401, 403, 422) - do not retry
                System.err.println("Logcore rejected batch (HTTP $statusCode): ${response.body()}")
                return false

            } catch (e: Exception) {
                if (attempt < MAX_RETRIES) {
                    val backoffMs = (1L shl attempt) * 250L
                    try {
                        Thread.sleep(backoffMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                    attempt++
                } else {
                    System.err.println("Logcore network error after $MAX_RETRIES retries: ${e.message}")
                    return false
                }
            }
        }

        return false
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            try {
                scheduler.shutdown()
                scheduler.awaitTermination(2, TimeUnit.SECONDS)
            } catch (_: Exception) {
                scheduler.shutdownNow()
            }
        }
    }
}
