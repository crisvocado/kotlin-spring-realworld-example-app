package io.realworld

import io.realworld.jwt.ExposeResponseInterceptor
import io.realworld.logcore.LogcoreAppender
import io.realworld.logcore.LogcoreTraceInterceptor
import jakarta.annotation.PostConstruct
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor
import org.springframework.web.servlet.config.annotation.CorsRegistry
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer


@Configuration
@EnableCaching
@SpringBootApplication
class ApiApplication : WebMvcConfigurer {

    @PostConstruct
    fun initLogcore() {
        LogcoreAppender.install()
    }

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(exposeResponseInterceptor())
        registry.addInterceptor(logcoreTraceInterceptor())
    }

    override fun addCorsMappings(registry: CorsRegistry) {
        registry.addMapping("/api/**")
                .allowedOrigins("*")
                .allowedMethods("*")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600)
        super.addCorsMappings(registry)
    }

    @Bean
    fun exposeResponseInterceptor() = ExposeResponseInterceptor()

    @Bean
    fun logcoreTraceInterceptor() = LogcoreTraceInterceptor()

    @Bean
    fun methodValidationPostProcessor(): MethodValidationPostProcessor {
        val mv = MethodValidationPostProcessor()
        mv.setValidator(validator())
        return mv
    }

    @Bean
    fun validator() = LocalValidatorFactoryBean()
}

fun main(args: Array<String>) {
    LogcoreAppender.install()
    SpringApplication.run(ApiApplication::class.java, *args)
}



