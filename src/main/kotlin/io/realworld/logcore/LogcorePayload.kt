package io.realworld.logcore

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

@JsonInclude(JsonInclude.Include.NON_NULL)
data class LogcoreEnvelope(
    @JsonProperty("schema_version")
    val schemaVersion: Int = 1,
    @JsonProperty("entries")
    val entries: List<LogcoreEntry>
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class LogcoreEntry(
    val timestamp: String,
    val severity: String,
    val message: String,
    val service: String,
    val env: String,
    @JsonProperty("insert_id")
    val insertId: String,
    val trace: LogcoreTrace? = null,
    val error: LogcoreError? = null,
    val labels: Map<String, String>? = null,
    val context: Map<String, Any>? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class LogcoreTrace(
    val traceId: String,
    val spanId: String? = null,
    val sampled: Boolean? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class LogcoreError(
    val type: String,
    val message: String,
    val stack: List<LogcoreStackFrame>
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class LogcoreStackFrame(
    val function: String,
    val file: String? = null,
    val line: Int? = null,
    val column: Int? = null,
    val inApp: Boolean
)
