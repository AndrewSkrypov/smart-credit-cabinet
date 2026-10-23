package ru.creditcabinet.common.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter
import ru.creditcabinet.common.exception.ApiException

/**
 * Turns a valid `Authorization: Bearer` token into the request's authenticated principal
 * ([TokenClaims], authority ROLE_<role>). An invalid or missing token leaves the request
 * anonymous: public endpoints still work, everything else is answered 401 by the chain.
 */
class JwtAuthenticationFilter(private val verifier: JwtVerifier) : OncePerRequestFilter() {
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
            }
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val CHECK_UNAVAILABLE = "cabinet.credentialsCheckUnavailable"
    }
}
