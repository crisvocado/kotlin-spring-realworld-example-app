package io.realworld.logcore

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LogcoreHttpTransport(
    private val config: LogcoreConfig,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build()
) {
    private val queue = ConcurrentLinkedDeque<LogcoreEntry>()
    private val queueSize = AtomicInteger(0)
    private val consecutiveFailures = AtomicInteger(0)
    private val isRunning = AtomicBoolean(true)
    private val mapper = ObjectMapper().registerKotlinModule()

    val ingestUrl: String = resolveIngestUrl(config.url)

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor(
        object : ThreadFactory {
            override fun newThread(r: Runnable): Thread {
                val t = Thread(r, "logcore-http-transport")
                t.isDaemon = true
                return t
            }
        }
    )

    init {
        scheduler.scheduleWithFixedDelay(
            { flushBatch(false) },
            5,
            5,
            TimeUnit.SECONDS
        )

        Runtime.getRuntime().addShutdownHook(
            Thread({
                close()
            }, "logcore-shutdown-hook")
        )
    }

    fun enqueue(entry: LogcoreEntry) {
        if ((!config.enabled && onSendBatch == null) || !isRunning.get()) return

        // Circuit breaker check: if 10 consecutive failures, drop buffer
        if (consecutiveFailures.get() >= MAX_CONSECUTIVE_FAILURES) {
            queue.clear()
            queueSize.set(0)
            return
        }

        // Buffer cap: 1000 entries; drop oldest
        while (queueSize.get() >= BUFFER_CAP) {
            if (queue.pollFirst() != null) {
                queueSize.decrementAndGet()
            } else {
                break
            }
        }

        queue.addLast(entry)
        val currentSize = queueSize.incrementAndGet()

        if (currentSize >= BATCH_TRIGGER_SIZE) {
            scheduler.execute { flushBatch(false) }
        }
    }

    fun flushBatch(all: Boolean = false) {
        if (!config.enabled && onSendBatch == null) return
        if (onSendBatch == null && (config.url.isNullOrBlank() || config.apiKey.isNullOrBlank())) return

        while (queueSize.get() > 0) {
            val batch = mutableListOf<LogcoreEntry>()
            while (batch.size < MAX_BATCH_SIZE) {
                val item = queue.pollFirst() ?: break
                queueSize.decrementAndGet()
                batch.add(item)
            }

            if (batch.isEmpty()) break

            val success = sendBatchWithRetry(batch)
            if (!success) {
                val failures = consecutiveFailures.incrementAndGet()
                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    System.err.println("[logcore] Circuit breaker tripped after $failures consecutive failures. Dropping buffer.")
                    queue.clear()
                    queueSize.set(0)
                }
                break
            } else {
                consecutiveFailures.set(0)
            }

            if (!all && queueSize.get() < BATCH_TRIGGER_SIZE) {
                break
            }
        }
    }

    private fun sendBatchWithRetry(entries: List<LogcoreEntry>): Boolean {
        val envelope = LogcoreEnvelope(schemaVersion = 1, entries = entries)
        val jsonPayload = try {
            mapper.writeValueAsString(envelope)
        } catch (e: Exception) {
            System.err.println("[logcore] Failed to serialize log entries: ${e.message}")
            return false
        }

        val localSendHook = onSendBatch
        if (localSendHook != null) {
            localSendHook.invoke(jsonPayload)
            return true
        }

        var attempt = 0
        while (attempt < MAX_RETRIES) {
            try {
                val request = HttpRequest.newBuilder()
                    .uri(URI.create(ingestUrl))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", config.apiKey ?: "")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build()

                val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
                val statusCode = response.statusCode()

                if (statusCode in 200..299) {
                    return true
                } else if (statusCode == 429 || statusCode >= 500) {
                    attempt++
                    if (attempt < MAX_RETRIES) {
                        val backoffMs = (1L shl attempt) * 250L
                        Thread.sleep(backoffMs)
                    }
                } else {
                    // Permanent 4xx error (e.g. 400, 422) - drop batch and echo server reason
                    System.err.println("[logcore] Permanent rejection ($statusCode): ${response.body()}")
                    return false
                }
            } catch (e: IOException) {
                attempt++
                if (attempt < MAX_RETRIES) {
                    val backoffMs = (1L shl attempt) * 250L
                    try {
                        Thread.sleep(backoffMs)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            } catch (e: Exception) {
                System.err.println("[logcore] Unexpected transport error: ${e.message}")
                return false
            }
        }
        return false
    }

    fun close() {
        if (isRunning.compareAndSet(true, false)) {
            try {
                flushBatch(true)
            } catch (e: Exception) {
                // Ignore during shutdown
            }
            scheduler.shutdown()
            try {
                if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow()
                }
            } catch (e: InterruptedException) {
                scheduler.shutdownNow()
            }
        }
    }

    companion object {
        const val BUFFER_CAP = 1000
        const val BATCH_TRIGGER_SIZE = 20
        const val MAX_BATCH_SIZE = 200
        const val MAX_RETRIES = 3
        const val MAX_CONSECUTIVE_FAILURES = 10

        @Volatile
        var onSendBatch: ((String) -> Unit)? = null

        @Volatile
        private var instance: LogcoreHttpTransport? = null

        fun getInstance(config: LogcoreConfig = LogcoreConfig.load()): LogcoreHttpTransport {
            return instance ?: synchronized(this) {
                instance ?: LogcoreHttpTransport(config).also { instance = it }
            }
        }

        fun flushSync() {
            instance?.flushBatch(true)
        }

        fun closeInstance() {
            instance?.close()
            instance = null
        }

        fun resolveIngestUrl(url: String?): String {
            if (url.isNullOrBlank()) return ""
            val clean = url.trim().trimEnd('/')
            return if (clean.endsWith("/v1/logs")) {
                clean
            } else {
                "$clean/v1/logs"
            }
        }
    }
}
