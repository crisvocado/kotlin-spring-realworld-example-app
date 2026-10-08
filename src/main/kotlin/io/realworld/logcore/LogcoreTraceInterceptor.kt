package io.realworld.logcore

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.servlet.HandlerInterceptor

class LogcoreTraceInterceptor : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val traceparent = request.getHeader("traceparent")
        val parsed = parseTraceparent(traceparent)
        if (parsed != null) {
            val (traceId, spanId, sampled) = parsed
            MDC.put("logcore.traceId", traceId)
            if (spanId != null) {
                MDC.put("logcore.spanId", spanId)
            }
            MDC.put("logcore.sampled", sampled.toString())

            // Pass traceparent to response
            val span = spanId ?: "0000000000000000"
            val sampledHex = if (sampled) "01" else "00"
            response.setHeader("traceparent", "00-$traceId-$span-$sampledHex")
        }

        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?
    ) {
        MDC.remove("logcore.traceId")
        MDC.remove("logcore.spanId")
        MDC.remove("logcore.sampled")
    }

    private fun parseTraceparent(header: String?): Triple<String, String?, Boolean>? {
        if (header.isNullOrBlank()) return null
        val parts = header.trim().split("-")
        if (parts.size >= 4 && parts[0] == "00" && parts[1].length == 32 && parts[2].length == 16) {
            val traceId = parts[1].lowercase()
            val spanId = parts[2].lowercase()
            if (traceId.matches(Regex("^[0-9a-f]{32}$")) && spanId.matches(Regex("^[0-9a-f]{16}$"))) {
                val flags = parts[3].toIntOrNull(16) ?: 0
                val sampled = (flags and 1) == 1
                return Triple(traceId, spanId, sampled)
            }
        }
        return null
    }
}
