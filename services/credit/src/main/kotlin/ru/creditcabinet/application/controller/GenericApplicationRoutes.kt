package ru.creditcabinet.application.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.HandlerMapping
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import ru.creditcabinet.application.application.ApplicationAccess
import ru.creditcabinet.common.exception.ForbiddenException
import ru.creditcabinet.common.security.CurrentCaller
import java.util.UUID

@Configuration
class GenericApplicationRoutes(private val applications: ApplicationAccess) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(object : HandlerInterceptor {
            override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
                if (CurrentCaller.claims().isAgent) {
                    if (request.method == "GET" || request.method == "HEAD") {
                        val variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<*, *>
                        val id = (variables?.get("id") as? String)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                        // Preserve object privacy for absent applications and revoked mandates.
                        id?.let(applications::get)
                    }
                    throw ForbiddenException("Агент работает с заявками в агентском кабинете")
                }
                return true
            }
        }).addPathPatterns("/api/v1/applications", "/api/v1/applications/**")
    }
}
