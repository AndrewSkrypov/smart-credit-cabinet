package ru.creditcabinet.common.limits

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import ru.creditcabinet.common.security.TokenClaims
import java.time.Duration

/** The protected assistant implementation keeps its own safeguard; this adds the shared cap. */
@Configuration
class AssistantLimitConfig(private val limits: SharedLimits) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(object : HandlerInterceptor {
            override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
                val claims = SecurityContextHolder.getContext().authentication?.principal as? TokenClaims
                if (limits.enabled && request.method == "POST" && claims != null) {
                    limits.acquire(
                        "Слишком много вопросов подряд — попробуйте через несколько минут",
                        LimitWindow("assistant", claims.userId.toString(), 20, Duration.ofMinutes(10)),
                    )
                }
                return true
            }
        }).addPathPatterns("/api/v1/assistant/messages")
    }
}
