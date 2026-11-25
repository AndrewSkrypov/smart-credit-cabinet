package ru.creditcabinet.common.security

import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.servlet.HandlerMapping
import ru.creditcabinet.common.exception.ApiException
import java.util.UUID

/**
 * Turns a valid `Authorization: Bearer` token into the request's authenticated principal
 * ([TokenClaims], authority ROLE_<role>). An invalid or missing token leaves the request
 * anonymous: public endpoints still work, everything else is answered 401 by the chain.
 */
class JwtAuthenticationFilter(
    private val verifier: JwtVerifier,
    private val metrics: MeterRegistry? = null,
    private val actorLogId: (UUID) -> String? = { null },
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (header != null && header.startsWith("Bearer ")) {
            val claims = try {
                verifier.parseClaims(header.removePrefix("Bearer "))
            } catch (exception: ApiException) {
                if (exception.httpStatus == HttpStatus.SERVICE_UNAVAILABLE) request.setAttribute(CHECK_UNAVAILABLE, exception.message)
                null
            } catch (_: Exception) {
                null
            }
            claims?.let { verified ->
                val authorities = listOfNotNull(verified.role?.let { SimpleGrantedAuthority("ROLE_$it") })
                SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(verified, null, authorities)
                MDC.putCloseable("userId", actorLogId(verified.userId)).use {
                    MDC.putCloseable("role", verified.role).use {
                        chain.doFilter(request, response)
                        if (verified.isAgent && response.status in setOf(403, 404)) {
                            metrics?.counter("cabinet.agent.access.denied", "status", response.status.toString())?.increment()
                        }
                        if (request.method in setOf("POST", "PUT", "PATCH", "DELETE") || response.status >= 400) {
                            val route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)?.toString() ?: "unmatched"
                            log.atInfo().addKeyValue("method", request.method).addKeyValue("route", route)
                                .addKeyValue("status", response.status).log("Cabinet request completed")
                        }
                    }
                }
                return
            }
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val CHECK_UNAVAILABLE = "cabinet.credentialsCheckUnavailable"
    }
}
