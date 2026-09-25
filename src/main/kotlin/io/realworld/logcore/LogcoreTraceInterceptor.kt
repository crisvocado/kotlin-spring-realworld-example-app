package io.realworld.logcore

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.servlet.HandlerInterceptor

class LogcoreTraceInterceptor : HandlerInterceptor {

    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        val traceparent = request.getHeader("traceparent") ?: request.getHeader("Traceparent")
        if (!traceparent.isNullOrBlank()) {
            val parts = traceparent.trim().split("-")
            if (parts.size >= 4) {
                val traceId = parts[1]
                val spanId = parts[2]
                val flags = parts[3]
                val sampled = flags == "01" || flags == "1"
                MDC.put(MDC_TRACE_ID, traceId)
                MDC.put(MDC_SPAN_ID, spanId)
                MDC.put(MDC_SAMPLED, sampled.toString())
            }
        }
        return true
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?
    ) {
        MDC.remove(MDC_TRACE_ID)
        MDC.remove(MDC_SPAN_ID)
        MDC.remove(MDC_SAMPLED)
    }

    companion object {
        const val MDC_TRACE_ID = "logcore_trace_id"
        const val MDC_SPAN_ID = "logcore_span_id"
        const val MDC_SAMPLED = "logcore_sampled"
    }
}
