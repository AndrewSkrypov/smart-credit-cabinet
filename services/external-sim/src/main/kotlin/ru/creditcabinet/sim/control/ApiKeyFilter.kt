package ru.creditcabinet.sim.control

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/**
 * The simulated systems are called by the bank's services, not by users: like the real
 * registries and the bureau they want a key of the calling system (X-Api-Key), not a user token.
 */
@Component
class ApiKeyFilter(@Value("\${cabinet.sim.api-key}") apiKey: String) : OncePerRequestFilter() {
    private val expected = apiKey.toByteArray()

    init {
        require(apiKey.length >= 12) { "cabinet.sim.api-key must be at least 12 characters" }
    }

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !request.requestURI.startsWith("/ext/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val given = request.getHeader(HEADER)?.toByteArray()
        if (given == null || !MessageDigest.isEqual(given, expected)) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            response.characterEncoding = Charsets.UTF_8.name()
            response.writer.write("""{"status":401,"error":"Unauthorized","message":"Нужен ключ системы-клиента в заголовке $HEADER"}""")
            return
        }
        chain.doFilter(request, response)
    }

    companion object {
        const val HEADER = "X-Api-Key"
    }
}
