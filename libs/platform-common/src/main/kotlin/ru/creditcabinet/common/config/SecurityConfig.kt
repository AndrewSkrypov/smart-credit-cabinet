package ru.creditcabinet.common.config

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.invoke
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.client.RestClient
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import ru.creditcabinet.common.dto.ErrorResponse
import ru.creditcabinet.common.security.CredentialsCheck
import ru.creditcabinet.common.security.IdentityCredentialsCheck
import ru.creditcabinet.common.security.JwtAuthenticationFilter
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.RevocationWatermarks
import java.time.Duration

/**
 * Endpoints a service serves without a signed-in user, as "METHOD /path/pattern", e.g.
 * "GET /api/v1/events/{id}" (Ant patterns in configuration). Everything not listed requires a valid token (deny by default).
 */
@ConfigurationProperties("cabinet.security")
data class PlatformSecurityProperties(
    val publicEndpoints: List<String> = emptyList(),
    /** Browser origins allowed to call the API cross-origin; empty = same origin only (no CORS). */
    val corsAllowedOrigins: List<String> = emptyList(),
)

@Configuration
@EnableConfigurationProperties(PlatformSecurityProperties::class)
class SecurityConfig {
    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    @Bean
    @ConditionalOnMissingBean
    fun jwtVerifier(
        @Value("\${cabinet.auth.jwt-secret}") secret: String,
        environment: Environment,
        credentialsCheck: ObjectProvider<CredentialsCheck>,
        revocations: RevocationWatermarks,
    ): JwtVerifier = JwtVerifier(secret, environment, credentialsCheck.ifAvailable, revocations)

    /**
     * Revoked tokens (password changed) are refused by services that know where identity is.
     * Identity itself declares its own check against its database.
     */
    @Bean
    @ConditionalOnExpression("!'\${cabinet.identity.url:}'.isBlank()")
    fun identityCredentialsCheck(
        @Value("\${cabinet.identity.url}") identityUrl: String,
        restClientBuilder: RestClient.Builder,
    ): CredentialsCheck {
        // Short timeouts: this runs inside other requests, a slow identity must not stall them.
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(1))
            setReadTimeout(Duration.ofSeconds(2))
        }
        return IdentityCredentialsCheck(restClientBuilder.clone().baseUrl(identityUrl).requestFactory(requestFactory).build())
    }

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        properties: PlatformSecurityProperties,
        jwtVerifier: JwtVerifier,
        objectMapper: ObjectMapper,
    ): SecurityFilterChain {
        val public = properties.publicEndpoints.map { entry ->
            val (method, pattern) = entry.trim().split(Regex("\\s+"), limit = 2).also {
                require(it.size == 2) { "cabinet.security.public-endpoints: expected \"METHOD /path\", got \"$entry\"" }
            }
            HttpMethod.valueOf(method.uppercase()) to pattern
        }
        http {
            csrf { disable() }
            httpBasic { disable() }
            formLogin { disable() }
            logout { disable() }
            sessionManagement { sessionCreationPolicy = SessionCreationPolicy.STATELESS }
            if (properties.corsAllowedOrigins.isNotEmpty()) {
                cors { configurationSource = corsSource(properties.corsAllowedOrigins) }
            } else {
                cors { disable() }
            }
            addFilterBefore<UsernamePasswordAuthenticationFilter>(JwtAuthenticationFilter(jwtVerifier))
            authorizeHttpRequests {
                // Probes, API docs and error rendering.
                authorize("/actuator/health/**", permitAll)
                authorize("/actuator/info", permitAll)
                authorize("/actuator/prometheus", permitAll)
                authorize("/v3/api-docs/**", permitAll)
                authorize("/swagger-ui/**", permitAll)
                authorize("/swagger-ui.html", permitAll)
                authorize("/error", permitAll)
                // Service-to-service API: not published by the ingress, and limited to the
                // platform's own pods by NetworkPolicy.
                authorize("/internal/**", permitAll)
                public.forEach { (method, pattern) -> authorize(method, pattern, permitAll) }
                authorize(anyRequest, authenticated)
            }
            exceptionHandling {
                authenticationEntryPoint = AuthenticationEntryPoint { request, response, _ ->
                    val unavailable = request.getAttribute(JwtAuthenticationFilter.CHECK_UNAVAILABLE) as? String
                    if (unavailable != null) {
                        writeError(objectMapper, request, response, HttpStatus.SERVICE_UNAVAILABLE, unavailable)
                    } else {
                        writeError(objectMapper, request, response, HttpStatus.UNAUTHORIZED, "Требуется вход в систему")
                    }
                }
                accessDeniedHandler = AccessDeniedHandler { request, response, _ ->
                    writeError(objectMapper, request, response, HttpStatus.FORBIDDEN, "Недостаточно прав для этого действия")
                }
            }
        }
        return http.build()
    }

    private fun corsSource(origins: List<String>) = UrlBasedCorsConfigurationSource().apply {
        registerCorsConfiguration(
            "/**",
            CorsConfiguration().apply {
                allowedOrigins = origins
                allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                allowedHeaders = listOf("Authorization", "Content-Type", "X-Request-Id", "Idempotency-Key")
                exposedHeaders = listOf("X-Request-Id")
                allowCredentials = false
                maxAge = 3600
            },
        )
    }

    private fun writeError(mapper: ObjectMapper, request: HttpServletRequest, response: HttpServletResponse, status: HttpStatus, message: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        mapper.writeValue(
            response.outputStream,
            ErrorResponse(status = status.value(), error = status.reasonPhrase, message = message, path = request.requestURI),
        )
    }
}
