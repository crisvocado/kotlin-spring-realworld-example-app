package io.realworld.logcore

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.servlet.HandlerInterceptor
import java.util.UUID

class LogcoreTraceInterceptor : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        try {
            val traceparent = request.getHeader("traceparent")
            val trace: LogcoreTrace = if (!traceparent.isNullOrBlank()) {
                val parts = traceparent.split("-")
                if (parts.size >= 4 && parts[1].length == 32 && parts[2].length == 16) {
                    val flags = parts[3].toIntOrNull(16) ?: 0
                    LogcoreTrace(traceId = parts[1], spanId = parts[2], sampled = (flags and 1) == 1)
                } else {
                    generateTrace()
                }
            } else {
                generateTrace()
            }

            MDC.put("traceId", trace.traceId)
            MDC.put("spanId", trace.spanId)
            MDC.put("sampled", trace.sampled.toString())

            val flag = if (trace.sampled) "01" else "00"
            response.setHeader("traceparent", "00-${trace.traceId}-${trace.spanId}-$flag")
        } catch (t: Throwable) {
            // Never break request flow
        }
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?
    ) {
        try {
            MDC.remove("traceId")
            MDC.remove("spanId")
            MDC.remove("sampled")
        } catch (t: Throwable) {
            // Ignore
        }
    }

    private fun generateTrace(): LogcoreTrace {
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId = UUID.randomUUID().toString().replace("-", "").take(16)
        return LogcoreTrace(traceId = traceId, spanId = spanId, sampled = true)
    }
}
