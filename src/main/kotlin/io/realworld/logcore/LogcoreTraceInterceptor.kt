package io.realworld.logcore

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.servlet.HandlerInterceptor
import java.util.UUID

class LogcoreTraceInterceptor : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val traceparent = request.getHeader("traceparent")
        val (traceId, spanId, sampled) = parseTraceparent(traceparent)

        MDC.put("logcore.traceId", traceId)
        MDC.put("logcore.spanId", spanId)
        MDC.put("logcore.sampled", sampled.toString())

        // Pass traceparent to response
        val sampledHex = if (sampled) "01" else "00"
        response.setHeader("traceparent", "00-$traceId-$spanId-$sampledHex")

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

    private fun parseTraceparent(header: String?): Triple<String, String, Boolean> {
        if (!header.isNullOrBlank()) {
            val parts = header.trim().split("-")
            if (parts.size >= 4 && parts[0] == "00" && parts[1].length == 32 && parts[2].length == 16) {
                val sampled = (parts[3].toIntOrNull(16) ?: 0) and 1 == 1
                return Triple(parts[1], parts[2], sampled)
            }
        }
        val traceId = UUID.randomUUID().toString().replace("-", "")
        val spanId = UUID.randomUUID().toString().replace("-", "").take(16)
        return Triple(traceId, spanId, true)
    }
}
