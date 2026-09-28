package io.realworld.logcore

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.web.filter.OncePerRequestFilter
import java.security.SecureRandom

class LogcoreTraceFilter : OncePerRequestFilter() {

    private val random = SecureRandom()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain
    ) {
        val incomingTraceparent = request.getHeader("traceparent")
        val trace = incomingTraceparent?.let { LogcoreEntryBuilder.parseTraceparent(it) }

        val traceId = trace?.traceId ?: generateHex(32)
        val spanId = trace?.spanId ?: generateHex(16)
        val sampled = trace?.sampled ?: true
        val flags = if (sampled) "01" else "00"
        val outgoingTraceparent = "00-$traceId-$spanId-$flags"

        MDC.put("traceId", traceId)
        MDC.put("spanId", spanId)
        MDC.put("traceSampled", sampled.toString())
        MDC.put("traceparent", outgoingTraceparent)

        response.setHeader("traceparent", outgoingTraceparent)

        try {
            filterChain.doFilter(request, response)
        } finally {
            MDC.remove("traceId")
            MDC.remove("spanId")
            MDC.remove("traceSampled")
            MDC.remove("traceparent")
        }
    }

    private fun generateHex(length: Int): String {
        val bytes = ByteArray(length / 2)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
