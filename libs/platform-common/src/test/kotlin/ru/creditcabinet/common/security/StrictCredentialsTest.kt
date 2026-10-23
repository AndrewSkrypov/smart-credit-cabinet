package ru.creditcabinet.common.security

import io.jsonwebtoken.Jwts
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.env.MockEnvironment
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withServerError
import org.springframework.test.web.client.response.MockRestResponseCreators.withStatus
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.exception.UnauthorizedException
import java.time.Instant
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StrictCredentialsTest {
    private val id = UUID.randomUUID()

    private fun verifier(check: CredentialsCheck) = JwtVerifier(JwtVerifier.DEV_DEFAULT_SECRET, MockEnvironment(), check)

    private fun token(verifier: JwtVerifier, version: Int): String = Jwts.builder().subject(id.toString())
        .claim(TokenClaims.ROLE, "CLIENT").claim(TokenClaims.CREDENTIALS_VERSION, version)
        .expiration(Date.from(Instant.now().plusSeconds(60))).signWith(verifier.signingKey).compact()

    @Test
    fun `an old or future credentials version is refused and an unknown version is not accepted`() {
        val verifier = verifier { 3 }
        assertEquals(3, verifier.parseClaims(token(verifier, 3)).credentialsVersion)
        for (version in listOf(2, 4)) assertFailsWith<UnauthorizedException> { verifier.parseClaims(token(verifier, version)) }
        val unavailable = verifier { null }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertFailsWith<ApiException> { unavailable.parseClaims(token(unavailable, 0)) }.httpStatus)
        val failed = verifier { error("database unavailable") }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertFailsWith<ApiException> { failed.parseClaims(token(failed, 0)) }.httpStatus)
    }

    @Test
    fun `every request reads authority and an outage never reuses a former positive answer`() {
        val builder = RestClient.builder().baseUrl("http://identity")
        val server = MockRestServiceServer.bindTo(builder).build()
        val url = "http://identity/internal/v1/users/$id/credentials"
        server.expect(requestTo(url)).andRespond(withSuccess("""{"credentialsVersion":0}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo(url)).andRespond(withSuccess("""{"credentialsVersion":1}""", MediaType.APPLICATION_JSON))
        server.expect(requestTo(url)).andRespond(withServerError())
        val check = IdentityCredentialsCheck(builder.build())
        assertEquals(0, check.currentVersion(id))
        assertEquals(1, check.currentVersion(id))
        assertNull(check.currentVersion(id))
        server.verify()
    }

    @Test
    fun `a missing user is revoked and a malformed authority response cannot mean version zero`() {
        val builder = RestClient.builder().baseUrl("http://identity")
        val server = MockRestServiceServer.bindTo(builder).build()
        val url = "http://identity/internal/v1/users/$id/credentials"
        server.expect(requestTo(url)).andRespond(withStatus(HttpStatus.NOT_FOUND))
        server.expect(requestTo(url)).andRespond(withSuccess("{}", MediaType.APPLICATION_JSON))
        val check = IdentityCredentialsCheck(builder.build())
        assertFailsWith<UnauthorizedException> { check.currentVersion(id) }
        assertNull(check.currentVersion(id))
        server.verify()
    }

    @Test
    fun `unknown authority is preserved for the entry point while public requests may continue anonymously`() {
        val verifier = verifier { null }
        val request = MockHttpServletRequest("GET", "/public").apply { addHeader("Authorization", "Bearer ${token(verifier, 0)}") }
        val response = MockHttpServletResponse()
        SecurityContextHolder.clearContext()
        try {
            JwtAuthenticationFilter(verifier).doFilter(request, response) { _, _ -> response.status = 200 }
            assertEquals(200, response.status)
            assertNull(SecurityContextHolder.getContext().authentication)
            assertEquals("Проверка сессии временно недоступна — попробуйте позже", request.getAttribute(JwtAuthenticationFilter.CHECK_UNAVAILABLE))
        } finally {
            SecurityContextHolder.clearContext()
        }
    }
}
