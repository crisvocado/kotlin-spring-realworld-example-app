package io.realworld.logcore

object LogcoreConfig {
    private const val DEFAULT_ENDPOINT = "https://logcore-dev-352942961463.us-east4.run.app"
    private const val DEFAULT_SERVICE = "api"
    private const val DEFAULT_ENV = "dev"
    private const val DEFAULT_MIN_SEVERITY = "ERROR"

    val enabled: Boolean
        get() {
            val v = System.getenv("LOGCORE_ENABLED")?.trim()?.lowercase()
            return when (v) {
                "false", "0", "no", "off" -> false
                else -> true
            }
        }

    val url: String
        get() {
            val envUrl = System.getenv("LOGCORE_URL")?.trim()
            return if (!envUrl.isNullOrEmpty()) envUrl else DEFAULT_ENDPOINT
        }

    val key: String
        get() = System.getenv("LOGCORE_KEY")?.trim() ?: ""

    val service: String
        get() {
            val s = System.getenv("LOGCORE_SERVICE")?.trim()
            return normalizeService(if (!s.isNullOrEmpty()) s else DEFAULT_SERVICE)
        }

    val env: String
        get() {
            val e = System.getenv("LOGCORE_ENV")?.trim()
            return normalizeEnv(if (!e.isNullOrEmpty()) e else DEFAULT_ENV)
        }

    val minSeverity: String
        get() {
            val m = System.getenv("LOGCORE_MIN_SEVERITY")?.trim()?.uppercase()
            return if (!m.isNullOrEmpty()) m else DEFAULT_MIN_SEVERITY
        }

    fun normalizeService(service: String): String {
        val s = service.trim().lowercase().replace(Regex("[^a-z0-9._-]"), "-")
        val trimmed = s.take(63).trim('.', '-', '_')
        return if (trimmed.isEmpty()) DEFAULT_SERVICE else trimmed
    }

    fun normalizeEnv(env: String): String {
        return when (env.trim().lowercase()) {
            "prod", "production" -> "prod"
            "staging", "stage" -> "staging"
            "test", "testing" -> "test"
            "local" -> "local"
            "dev", "development" -> "dev"
            else -> "dev"
        }
    }
}
