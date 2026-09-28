package io.realworld.logcore

object LogcoreConfig {
    private const val DEFAULT_URL = "https://logcore-dev-352942961463.us-east4.run.app"
    private const val DEFAULT_SERVICE = "api"
    private const val DEFAULT_ENV = "dev"
    private const val DEFAULT_MIN_SEVERITY = "ERROR"

    val url: String
        get() {
            val raw = System.getenv("LOGCORE_URL")
                ?: System.getProperty("LOGCORE_URL")
                ?: System.getProperty("logcore.url")
                ?: DEFAULT_URL
            return raw.trim().trimEnd('/')
        }

    val ingestUrl: String
        get() = "$url/v1/logs"

    val apiKey: String?
        get() = System.getenv("LOGCORE_KEY")
            ?: System.getProperty("LOGCORE_KEY")
            ?: System.getProperty("logcore.key")

    val service: String
        get() {
            val raw = System.getenv("LOGCORE_SERVICE")
                ?: System.getProperty("LOGCORE_SERVICE")
                ?: System.getProperty("logcore.service")
                ?: DEFAULT_SERVICE
            val normalized = raw.trim().lowercase()
            return if (normalized.matches(Regex("^[a-z0-9][a-z0-9._-]{0,62}$"))) normalized else DEFAULT_SERVICE
        }

    val env: String
        get() {
            val raw = System.getenv("LOGCORE_ENV")
                ?: System.getProperty("LOGCORE_ENV")
                ?: System.getProperty("logcore.env")
                ?: DEFAULT_ENV
            val normalized = raw.trim().lowercase()
            return when (normalized) {
                "prod", "production" -> "prod"
                "staging" -> "staging"
                "dev", "development" -> "dev"
                "test", "testing" -> "test"
                "local" -> "local"
                else -> DEFAULT_ENV
            }
        }

    val enabled: Boolean
        get() {
            val envVal = System.getenv("LOGCORE_ENABLED")
                ?: System.getProperty("LOGCORE_ENABLED")
                ?: System.getProperty("logcore.enabled")
            if (envVal != null) {
                return envVal.trim().lowercase() !in listOf("false", "0", "no", "off")
            }
            return true
        }

    val minSeverity: String
        get() = (System.getenv("LOGCORE_MIN_SEVERITY")
            ?: System.getProperty("LOGCORE_MIN_SEVERITY")
            ?: System.getProperty("logcore.min-severity")
            ?: DEFAULT_MIN_SEVERITY).trim().uppercase()
}
