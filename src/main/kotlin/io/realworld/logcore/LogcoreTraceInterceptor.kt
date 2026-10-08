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
            MDC.put("logcore.spanId", spanId)
            MDC.put("logcore.sampled", sampled.toString())

            val sampledHex = if (sampled) "01" else "00"
            response.setHeader("traceparent", "00-$traceId-$spanId-$sampledHex")
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

    fun parseTraceparent(header: String?): Triple<String, String, Boolean>? {
        if (header.isNullOrBlank()) return null
        val regex = Regex("^00-([0-9a-f]{32})-([0-9a-f]{16})-([0-9a-f]{2})$")
        val match = regex.matchEntire(header.trim()) ?: return null
        val traceId = match.groupValues[1]
        val spanId = match.groupValues[2]
        if (traceId == "00000000000000000000000000000000" || spanId == "0000000000000000") {
            return null
        }
        val flags = match.groupValues[3].toIntOrNull(16) ?: 0
        val sampled = (flags and 1) == 1
        return Triple(traceId, spanId, sampled)
    }
}
