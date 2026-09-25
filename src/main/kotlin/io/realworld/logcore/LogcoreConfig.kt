package io.realworld.logcore

import java.security.MessageDigest

data class LogcoreConfig(
    val enabled: Boolean = resolveEnabled(),
    val url: String = resolveUrl(),
    val apiKey: String? = resolveApiKey(),
    val service: String = resolveService(),
    val env: String = resolveEnv(),
    val minSeverity: String = resolveMinSeverity(),
    val batchSize: Int = 20,
    val maxBatchSize: Int = 200,
    val flushIntervalMs: Long = 5000L,
    val bufferCapacity: Int = 1000,
    val maxRetries: Int = 3,
    val maxConsecutiveFailures: Int = 10
) {
    companion object {
        fun resolveEnabled(): Boolean {
            val envEnabled = System.getenv("LOGCORE_ENABLED")
            if (!envEnabled.isNullOrBlank()) {
                return envEnabled.toBoolean()
            }
            return !System.getenv("LOGCORE_KEY").isNullOrBlank()
        }

        fun resolveUrl(): String {
            val envUrl = System.getenv("LOGCORE_URL")
            if (!envUrl.isNullOrBlank()) {
                return envUrl.trimEnd('/')
            }
            return "https://logcore-dev-352942961463.us-east4.run.app"
        }

        fun resolveApiKey(): String? {
            return System.getenv("LOGCORE_KEY")?.takeIf { it.isNotBlank() }
        }

        fun resolveService(): String {
            val raw = System.getenv("LOGCORE_SERVICE") ?: "kotlin-spring-realworld-example-app"
            val sanitized = raw.lowercase().replace(Regex("[^a-z0-9._-]"), "-").trim('-').take(63)
            return if (sanitized.isNotBlank() && sanitized.first().isLetterOrDigit()) sanitized else "kotlin-spring-realworld-example-app"
        }

        fun resolveEnv(): String {
            val raw = System.getenv("LOGCORE_ENV")
            return when (raw?.trim()?.lowercase()) {
                "production", "prod" -> "prod"
                "staging", "stage" -> "staging"
                "dev", "development" -> "dev"
                "test", "testing" -> "test"
                "local" -> "local"
                else -> "dev"
            }
        }

        fun resolveMinSeverity(): String {
            return System.getenv("LOGCORE_MIN_SEVERITY")?.uppercase() ?: "ERROR"
        }

        /**
         * Deterministic 32-char hex insert_id computed as sha256(timestamp | service | severity | message | contextJson)
         */
        fun computeInsertId(
            timestamp: String,
            service: String,
            severity: String,
            message: String,
            contextJson: String = ""
        ): String {
            val raw = "$timestamp|$service|$severity|$message|$contextJson"
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            val sb = StringBuilder(32)
            for (i in 0 until 16) {
                sb.append(String.format("%02x", digest[i]))
            }
            return sb.toString()
        }
    }
}
