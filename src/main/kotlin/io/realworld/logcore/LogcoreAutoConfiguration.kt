package io.realworld.logcore

import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered

@Configuration
class LogcoreAutoConfiguration {

    @PostConstruct
    fun init() {
        LogcoreAppender.attach()
    }

    @PreDestroy
    fun destroy() {
        LogcoreTransport.flush()
    }

    @Bean
    fun logcoreTraceFilter(): LogcoreTraceFilter = LogcoreTraceFilter()

    @Bean
    fun logcoreTraceFilterRegistration(filter: LogcoreTraceFilter): FilterRegistrationBean<LogcoreTraceFilter> {
        val registration = FilterRegistrationBean(filter)
        registration.addUrlPatterns("/*")
        registration.order = Ordered.HIGHEST_PRECEDENCE + 10
        return registration
    }
}
