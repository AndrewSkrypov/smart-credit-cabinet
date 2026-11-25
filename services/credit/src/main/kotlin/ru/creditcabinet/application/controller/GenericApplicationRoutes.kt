package ru.creditcabinet.application.controller

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import ru.creditcabinet.common.exception.ForbiddenException
import ru.creditcabinet.common.security.CurrentCaller

@Configuration
class GenericApplicationRoutes : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(object : HandlerInterceptor {
            override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
                if (CurrentCaller.claims().isAgent) {
                    throw ForbiddenException("Агент работает с заявками в агентском кабинете")
                }
                return true
            }
        }).addPathPatterns("/api/v1/applications", "/api/v1/applications/**")
    }
}
