package io.realworld.logcore

import java.util.Locale

data class LogcoreConfig(
    val url: String?,
    val apiKey: String?,
    val enabled: Boolean,
    val service: String,
    val env: String,
    val minSeverity: String
) {
    companion object {
        private val VALID_ENVS = setOf("prod", "staging", "dev", "test", "local")
        private val SERVICE_NAME_REGEX = Regex("^[a-z0-9][a-z0-9._-]{0,62}$")

        fun load(): LogcoreConfig {
            val rawUrl = System.getenv("LOGCORE_URL")?.trim()?.takeIf { it.isNotEmpty() }
                ?: "https://logcore-dev-352942961463.us-east4.run.app"
            val rawKey = System.getenv("LOGCORE_KEY")?.trim()?.takeIf { it.isNotEmpty() }
            val rawEnabled = System.getenv("LOGCORE_ENABLED")?.trim()?.lowercase(Locale.ROOT)

            val isExplicitlyDisabled = rawEnabled == "false" || rawEnabled == "0"
            val isExplicitlyEnabled = rawEnabled == "true" || rawEnabled == "1"

            val enabled = if (isExplicitlyDisabled) {
                false
            } else if (isExplicitlyEnabled) {
                true
            } else {
                !rawKey.isNullOrEmpty()
            }

            val rawService = System.getenv("LOGCORE_SERVICE")?.trim()?.lowercase(Locale.ROOT) ?: "api"
            val service = if (SERVICE_NAME_REGEX.matches(rawService)) {
                rawService
            } else {
                val cleaned = rawService.filter { it.isLetterOrDigit() || it == '.' || it == '_' || it == '-' }
                    .trimStart('.', '_', '-')
                    .take(63)
                if (SERVICE_NAME_REGEX.matches(cleaned)) cleaned else "api"
            }

            val rawEnv = System.getenv("LOGCORE_ENV")?.trim()?.lowercase(Locale.ROOT) ?: "dev"
            val env = when (rawEnv) {
                "production" -> "prod"
                in VALID_ENVS -> rawEnv
                else -> "dev"
            }

            val minSeverity = System.getenv("LOGCORE_MIN_SEVERITY")?.trim()?.uppercase(Locale.ROOT) ?: "ERROR"

            return LogcoreConfig(
                url = rawUrl,
                apiKey = rawKey,
                enabled = enabled,
                service = service,
                env = env,
                minSeverity = minSeverity
            )
        }
    }
}
