package ru.creditcabinet.auth

import com.fasterxml.jackson.databind.ObjectMapper
import org.mockito.kotlin.argThat
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import ru.creditcabinet.access.domain.AccessGrant
import ru.creditcabinet.auth.application.JwtService
import ru.creditcabinet.common.security.JwtVerifier
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(properties = ["cabinet.auth.jury-login-enabled=true"])
@AutoConfigureMockMvc
class JuryLoginIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var encoder: PasswordEncoder

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var verifier: JwtVerifier

    @MockitoSpyBean private lateinit var jwt: JwtService

    private val random = SecureRandom()
    private val accounts = mutableListOf<UUID>()

    @AfterTest
    fun removeAccounts() {
        accounts.forEach { jdbc.update("delete from users where id = ?", it) }
    }

    @Test
    fun `one time link creates a normal account session without enabling password authentication`() {
        val user = account()
        val token = grant(user)
        val response = redeem(token)
        assertEquals(200, response.status)
        assertEquals("no-store", response.getHeader(HttpHeaders.CACHE_CONTROL))
        val sessionJwt = mapper.readTree(response.contentAsString)["token"].asText()
        val claims = verifier.parseClaims(sessionJwt)
        assertEquals(user, claims.userId)
        assertEquals("UNDERWRITER", claims.role)
        assertEquals(2, claims.credentialsVersion)
        assertTrue(used(token))
        mockMvc.get("/api/v1/auth/session") { header(HttpHeaders.AUTHORIZATION, "Bearer $sessionJwt") }.andExpect {
            status { isOk() }
            jsonPath("$.user.fullName") { value("Тестовый член жюри") }
        }
        assertEquals(410, redeem(token).status)
        mockMvc.post("/api/v1/auth/sign-in") {
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(mapOf("email" to "$user@jury.test", "password" to TEST_PASSWORD))
        }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `unavailable links share the same response and remain unused`() {
        val active = account()
        val expired = grant(active, expired = true)
        val revoked = grant(active, revoked = true)
        val staleVersion = grant(active, credentialsVersion = 1)
        val blockedUser = grant(account(status = "BLOCKED"))
        val ordinaryUser = grant(account(juryEnabled = false))
        val unknownToken = randomToken()
        val unavailable = listOf(expired, revoked, staleVersion, blockedUser, ordinaryUser)
        val responses = (unavailable + listOf(unknownToken, "A".repeat(42) + "B", "invalid", "")).map(::redeem)
        assertTrue(responses.all { it.status == 410 && it.getHeader(HttpHeaders.CACHE_CONTROL) == "no-store" })
        assertEquals(1, responses.map { mapper.readTree(it.contentAsString)["message"].asText() }.toSet().size)
        assertFalse(unavailable.any(::used))
    }

    @Test
    fun `in parallel exactly one redemption succeeds`() {
        val token = grant(account())
        val ready = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val requests = List(2) {
                executor.submit(
                    Callable {
                        ready.countDown()
                        check(ready.await(5, TimeUnit.SECONDS))
                        redeem(token).status
                    },
                )
            }
            assertEquals(listOf(200, 410), requests.map { it.get(10, TimeUnit.SECONDS) }.sorted())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `a session issue failure rolls back grant consumption and sign-in time`() {
        val user = account()
        val token = grant(user)
        doThrow(IllegalStateException("test session failure")).doCallRealMethod().whenever(jwt).issue(argThat { id == user })
        assertEquals(500, redeem(token).status)
        assertFalse(used(token))
        assertEquals(null, jdbc.queryForObject("select last_sign_in_at from users where id = ?", java.sql.Timestamp::class.java, user))
        assertEquals(200, redeem(token).status)
    }

    private fun account(status: String = "ACTIVE", juryEnabled: Boolean = true): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            insert into users (id, full_name, email, role, status, password_hash, credentials_version, jury_link_enabled)
            values (?, 'Тестовый член жюри', ?, 'UNDERWRITER', ?, ?, 2, ?)
            """.trimIndent(),
            id,
            "$id@jury.test",
            status,
            encoder.encode(TEST_PASSWORD),
            juryEnabled,
        )
        accounts.add(id)
        return id
    }

    private fun grant(user: UUID, expired: Boolean = false, revoked: Boolean = false, credentialsVersion: Int = 2): String {
        val token = randomToken()
        jdbc.update(
            """
            insert into jury_login_grants (token_hash, user_id, credentials_version, created_at, expires_at, revoked_at)
            values (?, ?, ?, now() - interval '2 hours', now() + case when ? then interval '-1 hour' else interval '1 hour' end,
                case when ? then now() end)
            """.trimIndent(),
            AccessGrant.hash(token),
            user,
            credentialsVersion,
            expired,
            revoked,
        )
        return token
    }

    private fun randomToken(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also(random::nextBytes))

    private fun used(token: String) = jdbc.queryForObject(
        "select used_at is not null from jury_login_grants where token_hash = ?",
        Boolean::class.javaObjectType,
        AccessGrant.hash(token),
    ) == true

    private fun redeem(token: String): MockHttpServletResponse = mockMvc.post("/api/v1/auth/jury-link/redeem") {
        contentType = MediaType.APPLICATION_JSON
        content = mapper.writeValueAsString(mapOf("token" to token))
    }.andReturn().response

    companion object {
        private const val TEST_PASSWORD = "TestOnlyJuryPassword2026"
    }
}
