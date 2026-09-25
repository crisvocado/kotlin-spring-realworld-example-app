package io.realworld.logcore

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class LogcoreClient(
    val config: LogcoreConfig = LogcoreConfig(),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
) {
    private val bufferLock = Any()
    private val buffer = ArrayDeque<LogcoreEntry>()
    private val consecutiveFailures = AtomicInteger(0)
    private val objectMapper: ObjectMapper = ObjectMapper()
        .registerKotlinModule()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        val thread = Thread(runnable, "logcore-flusher")
        thread.isDaemon = true
        thread
    }

    var payloadListener: ((String, LogcorePayload) -> Unit)? = null
    var customSender: ((String) -> Boolean)? = null

    init {
        executor.scheduleWithFixedDelay(
            { flush() },
            config.flushIntervalMs,
            config.flushIntervalMs,
            TimeUnit.MILLISECONDS
        )

        Runtime.getRuntime().addShutdownHook(Thread {
            try {
                flush()
                executor.shutdown()
                executor.awaitTermination(2, TimeUnit.SECONDS)
            } catch (_: Exception) {
                // Best-effort shutdown
            }
        })
    }

    fun getIngestUrl(): String = "${config.url.trimEnd('/')}/v1/logs"

    fun enqueue(entry: LogcoreEntry) {
        var shouldTriggerFlush = false
        synchronized(bufferLock) {
            while (buffer.size >= config.bufferCapacity) {
                buffer.pollFirst() // Drop oldest
            }
            buffer.addLast(entry)
            if (buffer.size >= config.batchSize) {
                shouldTriggerFlush = true
            }
        }

        if (shouldTriggerFlush) {
            executor.submit { flush() }
        }
    }

    fun flush() {
        val batch = mutableListOf<LogcoreEntry>()
        synchronized(bufferLock) {
            if (consecutiveFailures.get() >= config.maxConsecutiveFailures) {
                buffer.clear()
                return
            }
            while (buffer.isNotEmpty() && batch.size < config.maxBatchSize) {
                batch.add(buffer.removeFirst())
            }
        }

        if (batch.isEmpty()) {
            return
        }

        val payload = LogcorePayload(schemaVersion = 1, entries = batch)
        val jsonPayload: String
        try {
            jsonPayload = objectMapper.writeValueAsString(payload)
        } catch (e: Exception) {
            System.err.println("Logcore failed to serialize payload: ${e.message}")
            return
        }

        payloadListener?.invoke(jsonPayload, payload)

        if (customSender != null) {
            val ok = customSender!!.invoke(jsonPayload)
            if (ok) {
                consecutiveFailures.set(0)
            } else {
                handleFailure()
            }
            return
        }

        if (!config.enabled || config.apiKey.isNullOrBlank()) {
            // Disabled or no key configured: discard batch safely
            return
        }

        val request = HttpRequest.newBuilder()
            .uri(URI.create(getIngestUrl()))
            .header("Content-Type", "application/json")
            .header("x-api-key", config.apiKey)
            .timeout(Duration.ofSeconds(10))
            .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
            .build()

        var attempt = 0
        var delivered = false

        while (attempt < config.maxRetries && !delivered) {
            try {
                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                val statusCode = response.statusCode()
                if (statusCode in 200..299) {
                    delivered = true
                    consecutiveFailures.set(0)
                } else if (statusCode == 429 || statusCode in 500..599) {
                    attempt++
                    if (attempt < config.maxRetries) {
                        val backoffMs = (1L shl attempt) * 250L
                        try {
                            Thread.sleep(backoffMs)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                } else if (statusCode in 400..499) {
                    // Permanent 4xx error: drop batch and echo server reason to stderr
                    System.err.println("Logcore rejected batch with HTTP $statusCode: ${response.body()}")
                    consecutiveFailures.set(0)
                    return
                } else {
                    attempt++
                }
            } catch (e: Exception) {
                attempt++
                if (attempt < config.maxRetries) {
                    val backoffMs = (1L shl attempt) * 250L
                    try {
                        Thread.sleep(backoffMs)
                    } catch (_: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    }
                }
            }
        }

        if (!delivered) {
            handleFailure()
        }
    }

    private fun handleFailure() {
        val failures = consecutiveFailures.incrementAndGet()
        if (failures >= config.maxConsecutiveFailures) {
            System.err.println("Logcore circuit breaker tripped after $failures consecutive failures. Clearing buffer.")
            synchronized(bufferLock) {
                buffer.clear()
            }
        }
    }

    fun close() {
        try {
            flush()
            executor.shutdown()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: Exception) {
        }
    }

    companion object {
        val instance by lazy { LogcoreClient() }
    }
}
