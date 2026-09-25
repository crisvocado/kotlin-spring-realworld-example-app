package io.realworld.logcore

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object LogcoreTransport {
    private const val MAX_BUFFER_CAPACITY = 1000
    private const val BATCH_SIZE_TRIGGER = 20
    private const val MAX_BATCH_SIZE = 200
    private const val MAX_CONSECUTIVE_FAILURES = 10

    private val queue = ArrayBlockingQueue<LogcoreEntry>(MAX_BUFFER_CAPACITY)
    private val queueLock = Any()
    private val consecutiveFailures = AtomicInteger(0)
    private val isStarted = AtomicBoolean(false)
    private val isShuttingDown = AtomicBoolean(false)

    val objectMapper: ObjectMapper = jacksonObjectMapper()
        .setSerializationInclusion(JsonInclude.Include.NON_NULL)
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build()
    }

    private var scheduler: ScheduledExecutorService? = null

    // Optional hook for unit tests and probe to intercept serialized payload without network
    var transportInterceptor: ((String) -> Unit)? = null
    var stubNetwork: Boolean = false

    init {
        start()
        try {
            Runtime.getRuntime().addShutdownHook(Thread({
                flushAll()
                stop()
            }, "logcore-shutdown-hook"))
        } catch (ignored: Throwable) {
        }
    }

    @Synchronized
    fun start() {
        if (isStarted.compareAndSet(false, true)) {
            isShuttingDown.set(false)
            val exec = Executors.newSingleThreadScheduledExecutor { r ->
                val t = Thread(r, "logcore-transport-worker")
                t.isDaemon = true
                t
            }
            scheduler = exec
            exec.scheduleWithFixedDelay({
                try {
                    flush()
                } catch (t: Throwable) {
                    System.err.println("Logcore background flush error: ${t.message}")
                }
            }, 5, 5, TimeUnit.SECONDS)
        }
    }

    @Synchronized
    fun stop() {
        if (isShuttingDown.compareAndSet(false, true)) {
            isStarted.set(false)
            try {
                scheduler?.shutdownNow()
            } catch (ignored: Throwable) {
            } finally {
                scheduler = null
            }
        }
    }

    fun enqueue(entry: LogcoreEntry) {
        if (!LogcoreConfig.enabled) return
        if (consecutiveFailures.get() >= MAX_CONSECUTIVE_FAILURES) {
            // Circuit breaker tripped: drop entries
            return
        }

        var shouldFlushImmediately = false
        synchronized(queueLock) {
            while (queue.size >= MAX_BUFFER_CAPACITY) {
                queue.poll() // Drop oldest entry
            }
            queue.offer(entry)
            if (queue.size >= BATCH_SIZE_TRIGGER) {
                shouldFlushImmediately = true
            }
        }

        if (shouldFlushImmediately) {
            try {
                scheduler?.execute { flush() }
            } catch (ignored: Throwable) {
            }
        }
    }

    fun flush() {
        val batch = mutableListOf<LogcoreEntry>()
        synchronized(queueLock) {
            while (batch.size < MAX_BATCH_SIZE && !queue.isEmpty()) {
                val item = queue.poll() ?: break
                batch.add(item)
            }
        }

        if (batch.isNotEmpty()) {
            sendBatchWithRetry(batch)
        }
    }

    fun flushAll() {
        while (true) {
            val batch = mutableListOf<LogcoreEntry>()
            synchronized(queueLock) {
                while (batch.size < MAX_BATCH_SIZE && !queue.isEmpty()) {
                    val item = queue.poll() ?: break
                    batch.add(item)
                }
            }
            if (batch.isEmpty()) break
            sendBatchWithRetry(batch)
        }
    }

    fun sendBatchWithRetry(entries: List<LogcoreEntry>): Boolean {
        if (entries.isEmpty()) return true
        val envelope = LogcoreEnvelope(schemaVersion = 1, entries = entries)
        val jsonPayload: String
        try {
            jsonPayload = objectMapper.writeValueAsString(envelope)
        } catch (t: Throwable) {
            System.err.println("Logcore JSON serialization failed: ${t.message}")
            return false
        }

        transportInterceptor?.let { interceptor ->
            try {
                interceptor(jsonPayload)
            } catch (t: Throwable) {
                System.err.println("Logcore transport interceptor error: ${t.message}")
            }
        }

        if (stubNetwork) {
            return true
        }

        val url = LogcoreConfig.url
        val key = LogcoreConfig.key
        val endpoint = "${url.trimEnd('/')}/v1/logs"

        for (attempt in 0..3) {
            if (attempt > 0) {
                val backoffMs = (Math.pow(2.0, (attempt - 1).toDouble()) * 250).toLong()
                try {
                    Thread.sleep(backoffMs)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }

            try {
                val requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, Charsets.UTF_8))

                if (key.isNotEmpty()) {
                    requestBuilder.header("x-api-key", key)
                }

                val response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
                val statusCode = response.statusCode()

                if (statusCode in 200..299) {
                    consecutiveFailures.set(0)
                    return true
                } else if (statusCode == 429 || statusCode >= 500) {
                    // Transient retryable error
                    if (attempt == 3) {
                        recordFailure("Logcore server returned HTTP $statusCode after retries: ${response.body()}")
                    }
                    continue
                } else {
                    // Permanent 4xx error (e.g. 400, 401, 403, 422)
                    System.err.println("Logcore rejected batch (HTTP $statusCode): ${response.body()}")
                    consecutiveFailures.set(0)
                    return false
                }
            } catch (e: Exception) {
                if (attempt == 3) {
                    recordFailure("Logcore delivery failed after retries: ${e.message}")
                }
            }
        }
        return false
    }

    private fun recordFailure(reason: String) {
        val failures = consecutiveFailures.incrementAndGet()
        if (failures >= MAX_CONSECUTIVE_FAILURES) {
            System.err.println("Logcore circuit breaker tripped after $failures consecutive failures. Clearing queue.")
            synchronized(queueLock) {
                queue.clear()
            }
        } else {
            System.err.println(reason)
        }
    }
}
