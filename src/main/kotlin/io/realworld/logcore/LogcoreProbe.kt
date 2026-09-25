package io.realworld.logcore

import org.slf4j.LoggerFactory

object LogcoreProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        runProbe()
    }
}

fun main() {
    runProbe()
}

private fun runProbe() {
    var capturedEntryJson: String? = null
    LogcoreTransport.stubNetwork = true
    LogcoreTransport.transportInterceptor = { payload ->
        try {
            val tree = LogcoreTransport.objectMapper.readTree(payload)
            val entries = tree.get("entries")
            if (entries != null && entries.isArray && entries.size() > 0) {
                capturedEntryJson = LogcoreTransport.objectMapper.writeValueAsString(entries.get(0))
            }
        } catch (ignored: Throwable) {
        }
    }

    LogcoreAppender.ensureAttached()
    val logger = LoggerFactory.getLogger(LogcoreProbe::class.java)
    val probeException = IllegalStateException("Logcore verification probe exception")
    logger.error("Logcore integration probe verification error", probeException)
    LogcoreTransport.flushAll()
    LogcoreTransport.stop()

    capturedEntryJson?.let {
        println(it)
    }
}
