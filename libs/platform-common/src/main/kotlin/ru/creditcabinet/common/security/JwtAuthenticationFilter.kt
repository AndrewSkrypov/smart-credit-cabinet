package ru.creditcabinet.common.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Turns a valid `Authorization: Bearer` token into the request's authenticated principal
 * ([TokenClaims], authority ROLE_<role>). An invalid or missing token leaves the request
 * anonymous: public endpoints still work, everything else is answered 401 by the chain.
 */
class JwtAuthenticationFilter(private val verifier: JwtVerifier) : OncePerRequestFilter() {
    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val header = request.getHeader(HttpHeaders.AUTHORIZATION)
        if (header != null && header.startsWith("Bearer ")) {
            runCatching { verifier.parseClaims(header.removePrefix("Bearer ")) }.getOrNull()?.let { claims ->
                val authorities = listOfNotNull(claims.role?.let { SimpleGrantedAuthority("ROLE_$it") })
                SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(claims, null, authorities)
            }
        }
        chain.doFilter(request, response)
    }
}
