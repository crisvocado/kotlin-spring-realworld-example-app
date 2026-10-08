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
    @JsonProperty("timestamp")
    val timestamp: String,
    @JsonProperty("severity")
    val severity: String,
    @JsonProperty("message")
    val message: String,
    @JsonProperty("service")
    val service: String,
    @JsonProperty("env")
    val env: String,
    @JsonProperty("insert_id")
    val insertId: String,
    @JsonProperty("trace")
    val trace: LogcoreTrace? = null,
    @JsonProperty("error")
    val error: LogcoreError? = null,
    @JsonProperty("labels")
    val labels: Map<String, String>? = null,
    @JsonProperty("context")
    val context: Map<String, Any>? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class LogcoreTrace(
    @JsonProperty("traceId")
    val traceId: String,
    @JsonProperty("spanId")
    val spanId: String? = null,
    @JsonProperty("sampled")
    val sampled: Boolean = true
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class LogcoreError(
    @JsonProperty("type")
    val type: String,
    @JsonProperty("message")
    val message: String,
    @JsonProperty("stack")
    val stack: List<LogcoreStackFrame>
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class LogcoreStackFrame(
    @JsonProperty("function")
    val function: String,
    @JsonProperty("file")
    val file: String? = null,
    @JsonProperty("line")
    val line: Int? = null,
    @JsonProperty("column")
    val column: Int? = null,
    @get:JsonProperty("inApp")
    val inApp: Boolean
)
