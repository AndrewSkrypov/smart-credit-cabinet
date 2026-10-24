package ru.creditcabinet.access

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import ru.creditcabinet.access.application.CompanyAccessService
import ru.creditcabinet.access.application.PasswordAccessService
import ru.creditcabinet.access.domain.LinkPurpose
import ru.creditcabinet.access.dto.ResetPasswordRequest
import ru.creditcabinet.access.infrastructure.IdentityLinks
import ru.creditcabinet.access.infrastructure.SecurityMailDelivery
import ru.creditcabinet.auth.application.JwtService
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.mail.MailCapabilities
import ru.creditcabinet.common.mail.MailTransport
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.user.domain.User
import ru.creditcabinet.user.domain.UserRole
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AccessTestClock : Clock() {
    @Volatile var now: Instant = Instant.parse("2026-10-22T12:00:00Z")
    override fun instant(): Instant = now
    override fun getZone(): ZoneId = ZoneOffset.UTC
    override fun withZone(zone: ZoneId): Clock = this
}

@TestConfiguration
class AccessClockConfiguration {
    @Bean
    fun accessClock() = AccessTestClock()
}

@SpringBootTest
@AutoConfigureMockMvc
@Import(AccessClockConfiguration::class)
class AccountAccessIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var encoder: PasswordEncoder

    @Autowired private lateinit var jwt: JwtService

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var links: IdentityLinks

    @Autowired private lateinit var clock: AccessTestClock

    @Autowired private lateinit var passwords: PasswordAccessService

    @Autowired private lateinit var companies: CompanyAccessService

    @Autowired private lateinit var delivery: SecurityMailDelivery

    @MockitoBean private lateinit var mail: MailCapabilities

    @MockitoBean private lateinit var transport: MailTransport

    @BeforeTest
    fun defaults() {
        clock.now = Instant.parse("2026-10-22T12:00:00Z")
        whenever(mail.enabled()).thenReturn(true)
    }

    private fun user(company: UUID = UUID.randomUUID(), sign: Boolean = true, role: UserRole = UserRole.CLIENT): User {
        val id = UUID.randomUUID()
        jdbc.update(
            "insert into companies (id,inn,name) values (?,?,?) on conflict(id) do nothing",
            company,
            id.toString().filter(Char::isDigit).take(12).padEnd(12, '0'),
            "Тестовая компания",
        )
        val user = User(
            id = id,
            fullName = "Тестовый сотрудник",
            email = "$id@example.test",
            role = role,
            companyId = if (role ==
                UserRole.CLIENT
            ) {
                company
            } else {
                null
            },
            canSign = role == UserRole.CLIENT && sign,
        )
        jdbc.update(
            "insert into users (id,full_name,email,role,company_id,can_sign,password_hash) values (?,?,?,?,?,?,?)",
            user.id,
            user.fullName,
            user.email,
            role.name,
            user.companyId,
            user.canSign,
            encoder.encode("Secret2026"),
        )
        return user
    }

    private fun post(path: String, body: Any = emptyMap<String, String>(), token: String? = null, key: UUID? = null): MockHttpServletResponse = mvc.post(path) {
        contentType = MediaType.APPLICATION_JSON
        content = mapper.writeValueAsString(body)
        token?.let { header("Authorization", "Bearer $it") }
        key?.let { header("Idempotency-Key", it) }
        header("X-Real-IP", UUID.randomUUID().toString())
    }.andReturn().response

    private fun json(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.getContentAsString(Charsets.UTF_8))
    private fun invite(
        owner: User,
        sign: Boolean = false,
        key: UUID = UUID.randomUUID(),
        email: String = "${UUID.randomUUID()}@example.test",
    ): MockHttpServletResponse =
        post("/api/v1/companies/me/invitations", mapOf("email" to email, "canSign" to sign), jwt.issue(owner), key)
    private fun accept(
        token: String,
    ): MockHttpServletResponse = post(
        "/api/v1/auth/invitations/accept",
        mapOf(
            "token" to token,
            "fullName" to "Новый сотрудник",
            "password" to "NewSecret2026",
            "personalDataConsent" to true,
        ),
    )
    private fun access(token: String) = mvc.get("/api/v1/companies/me/employees") { header("Authorization", "Bearer $token") }.andReturn().response
    private fun resetId(
        user: User,
    ): UUID = jdbc.queryForObject(
        "select id from password_reset_requests where user_id=? and revoked_at is null and used_at is null",
        UUID::class.java,
        user.id,
    )!!

    @Test
    fun `invalid account forms explain their fields in Russian`() {
        val actor = jwt.issue(user())
        val cases = listOf(
            Triple("/api/v1/auth/password-recovery", mapOf("email" to "wrong"), "Email должен быть корректным"),
            Triple("/api/v1/auth/password-reset", mapOf("token" to "", "password" to "Updated2026"), "Откройте одноразовую ссылку"),
            Triple("/api/v1/auth/password", mapOf("currentPassword" to "", "password" to "Updated2026"), "Укажите текущий пароль"),
            Triple("/api/v1/auth/password", mapOf("currentPassword" to "я".repeat(40), "password" to "Updated2026"), "Текущий пароль слишком длинный"),
        )
        for ((path, body, message) in cases) {
            val response = post(path, body, actor)
            assertEquals(400, response.status)
            assertTrue(json(response)["message"].asText().contains(message))
        }
    }

    @Test
    fun `recovery is opaque hash-only one-use and revokes every previous session`() {
        val user = user()
        val old = jwt.issue(user)
        val known = post("/api/v1/auth/password-recovery", mapOf("email" to user.email))
        val unknown = post("/api/v1/auth/password-recovery", mapOf("email" to "${UUID.randomUUID()}@example.test"))
        assertEquals(200, known.status)
        assertEquals(known.contentAsString, unknown.contentAsString)
        val id = resetId(user)
        val raw = links.token(LinkPurpose.PASSWORD_RESET, id)
        val hash = jdbc.queryForObject("select token_hash from password_reset_requests where id=?", String::class.java, id)!!
        assertEquals(64, hash.length)
        assertNotEquals(raw, hash)
        val payload = jdbc.queryForObject("select payload::text from delivery_outbox where event_key=?", String::class.java, "password-reset:$id")!!
        assertFalse(payload.contains(raw))
        assertFalse(payload.contains(user.email))
        delivery.deliver(payload)
        verify(transport).send(any())
        assertEquals(400, post("/api/v1/auth/password-reset", mapOf("token" to raw, "password" to "short")).status)
        assertEquals(200, post("/api/v1/auth/password-reset", mapOf("token" to raw, "password" to "Updated2026")).status)
        assertEquals(410, post("/api/v1/auth/password-reset", mapOf("token" to raw, "password" to "Updated2026")).status)
        assertEquals(401, access(old).status)
        assertEquals(401, post("/api/v1/auth/sign-in", mapOf("email" to user.email, "password" to "Secret2026")).status)
        val signedIn = post("/api/v1/auth/sign-in", mapOf("email" to user.email, "password" to "Updated2026"))
        assertEquals(200, signedIn.status)
        assertEquals(200, access(json(signedIn)["token"].asText()).status)
        assertEquals(1, jdbc.queryForObject("select count(*) from identity_audit where subject_id=? and action='PASSWORD_RESET'", Int::class.java, user.id))
    }

    @Test
    fun `new requests expiry and concurrent consumes cannot reuse a reset grant`() {
        val user = user()
        passwords.requestRecovery(user.email, UUID.randomUUID().toString())
        val old = links.token(LinkPurpose.PASSWORD_RESET, resetId(user))
        passwords.requestRecovery(user.email, UUID.randomUUID().toString())
        val current = links.token(LinkPurpose.PASSWORD_RESET, resetId(user))
        assertEquals(410, post("/api/v1/auth/password-reset", mapOf("token" to old, "password" to "Updated2026")).status)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(
                List(2) {
                    Callable {
                        try {
                            passwords.resetPassword(ResetPasswordRequest(current, "Updated2026"))
                            200
                        } catch (e: ApiException) {
                            e.httpStatus.value()
                        }
                    }
                },
            ).map { it.get(10, TimeUnit.SECONDS) }.sorted()
            assertEquals(listOf(200, 410), results)
        } finally {
            pool.shutdownNow()
        }
        passwords.requestRecovery(user.email, UUID.randomUUID().toString())
        val expires = links.token(LinkPurpose.PASSWORD_RESET, resetId(user))
        clock.now = clock.now.plusSeconds(1800)
        assertEquals(410, post("/api/v1/auth/password-reset", mapOf("token" to expires, "password" to "Updated2026")).status)
    }

    @Test
    fun `disabled mail and per-address limits reveal no account membership`() {
        val user = user()
        whenever(mail.enabled()).thenReturn(false)
        val known = post("/api/v1/auth/password-recovery", mapOf("email" to user.email))
        val unknown = post("/api/v1/auth/password-recovery", mapOf("email" to "absent-${UUID.randomUUID()}@example.test"))
        assertEquals(503, known.status)
        assertEquals(json(known)["message"], json(unknown)["message"])
        whenever(mail.enabled()).thenReturn(true)
        repeat(3) { assertEquals(200, post("/api/v1/auth/password-recovery", mapOf("email" to user.email.uppercase())).status) }
        assertEquals(429, post("/api/v1/auth/password-recovery", mapOf("email" to user.email)).status)
        assertEquals(1, jdbc.queryForObject("select count(*) from password_reset_requests where user_id=? and revoked_at is null", Int::class.java, user.id))
    }

    @Test
    fun `password requires current password and logout cancels outstanding recovery links`() {
        val user = user()
        val token = jwt.issue(user)
        assertEquals(400, post("/api/v1/auth/password", mapOf("currentPassword" to "wrong", "password" to "Updated2026"), token).status)
        assertEquals(200, access(token).status)
        passwords.requestRecovery(user.email, UUID.randomUUID().toString())
        val link = links.token(LinkPurpose.PASSWORD_RESET, resetId(user))
        assertEquals(200, post("/api/v1/auth/password", mapOf("currentPassword" to "Secret2026", "password" to "Updated2026"), token).status)
        assertEquals(401, access(token).status)
        assertEquals(410, post("/api/v1/auth/password-reset", mapOf("token" to link, "password" to "Updated2026")).status)
        val fresh = json(post("/api/v1/auth/sign-in", mapOf("email" to user.email, "password" to "Updated2026")))["token"].asText()
        passwords.requestRecovery(user.email, UUID.randomUUID().toString())
        val pending = links.token(LinkPurpose.PASSWORD_RESET, resetId(user))
        assertEquals(200, post("/api/v1/auth/logout-all", token = fresh).status)
        assertEquals(401, access(fresh).status)
        assertEquals(410, post("/api/v1/auth/password-reset", mapOf("token" to pending, "password" to "Updated2026")).status)
        assertEquals(1, jdbc.queryForObject("select count(*) from identity_audit where subject_id=? and action='SESSIONS_REVOKED'", Int::class.java, user.id))
    }

    @Test
    fun `profile name changes freely, a new email needs the current password and becomes the login`() {
        val user = user()
        val other = user()
        val token = jwt.issue(user)
        fun update(body: Map<String, String>) = mvc.put("/api/v1/users/me") {
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(body)
            header("Authorization", "Bearer $token")
        }.andReturn().response

        val renamed = update(mapOf("fullName" to "  Анна   Котова ", "email" to user.email))
        assertEquals(200, renamed.status)
        assertEquals("Анна Котова", json(renamed)["user"]["fullName"].asText())
        assertEquals("Анна Котова", verifier.parseClaims(json(renamed)["token"].asText()).fullName)
        val email = "${UUID.randomUUID()}@Example.test"
        assertEquals(400, update(mapOf("fullName" to "Анна Котова", "email" to email)).status)
        assertEquals(400, update(mapOf("fullName" to "Анна Котова", "email" to email, "currentPassword" to "wrong")).status)
        assertEquals(409, update(mapOf("fullName" to "Анна Котова", "email" to other.email, "currentPassword" to "Secret2026")).status)
        passwords.requestRecovery(user.email, UUID.randomUUID().toString())
        val link = links.token(LinkPurpose.PASSWORD_RESET, resetId(user))
        val moved = update(mapOf("fullName" to "Анна Котова", "email" to email, "currentPassword" to "Secret2026"))
        assertEquals(200, moved.status)
        assertEquals(email.lowercase(), json(moved)["user"]["email"].asText())
        // The old address no longer signs in or resets the password; the old token still works until it expires.
        assertEquals(401, post("/api/v1/auth/sign-in", mapOf("email" to user.email, "password" to "Secret2026")).status)
        assertEquals(200, post("/api/v1/auth/sign-in", mapOf("email" to email.lowercase(), "password" to "Secret2026")).status)
        assertEquals(410, post("/api/v1/auth/password-reset", mapOf("token" to link, "password" to "Updated2026")).status)
        assertEquals(200, access(token).status)
        val actions = jdbc.queryForList("select action from identity_audit where subject_id=?", String::class.java, user.id)
        assertTrue("NAME_CHANGED" in actions && "EMAIL_CHANGED" in actions, actions.toString())
        assertEquals(400, update(mapOf("fullName" to " ", "email" to email)).status)
    }

    @Test
    fun `invitation is revealed once idempotent rotated and consumed by a non-signing employee`() {
        whenever(mail.enabled()).thenReturn(false)
        val owner = user()
        val email = "${UUID.randomUUID()}@example.test"
        val key = UUID.randomUUID()
        val first = invite(owner, key = key, email = email)
        assertEquals(200, first.status)
        val firstJson = json(first)
        val firstToken = firstJson["link"].asText().substringAfter("#token=")
        val replay = invite(owner, key = key, email = email)
        assertEquals(firstJson["id"], json(replay)["id"])
        assertTrue(json(replay)["link"].isNull)
        assertEquals(409, invite(owner, sign = true, key = key, email = email).status)
        val id = firstJson["id"].asText()
        val next = post("/api/v1/companies/me/invitations/$id/resend", token = jwt.issue(owner), key = UUID.randomUUID())
        assertEquals(200, next.status)
        assertEquals(410, accept(firstToken).status)
        val token = json(next)["link"].asText().substringAfter("#token=")
        val accepted = accept(token)
        assertEquals(200, accepted.status)
        assertFalse(json(accepted)["user"]["canSign"].asBoolean())
        val employeeToken = json(accepted)["token"].asText()
        assertEquals(200, access(employeeToken).status)
        assertEquals(403, post("/api/v1/companies/me/invitations", mapOf("email" to "new@example.test"), employeeToken, UUID.randomUUID()).status)
        assertEquals(410, accept(token).status)
        val memberId = UUID.fromString(json(accepted)["user"]["id"].asText())
        val revoked = mvc.delete("/api/v1/companies/me/employees/$memberId/access") {
            header("Authorization", "Bearer ${jwt.issue(owner)}")
            header("Idempotency-Key", UUID.randomUUID())
        }.andReturn().response
        assertEquals(200, revoked.status)
        assertEquals(401, access(employeeToken).status)
        assertEquals(403, post("/api/v1/auth/sign-in", mapOf("email" to email, "password" to "NewSecret2026")).status)
        assertEquals(1, jdbc.queryForObject("select count(*) from identity_audit where subject_id=? and action='EMPLOYEE_REVOKED'", Int::class.java, memberId))
    }

    @Test
    fun `foreign objects staff and non-signers cannot manage a company and expired invitations are refused`() {
        whenever(mail.enabled()).thenReturn(false)
        val owner = user()
        val foreign = user()
        val invitation = json(invite(owner))
        val id = invitation["id"].asText()
        assertEquals(404, post("/api/v1/companies/me/invitations/$id/resend", token = jwt.issue(foreign), key = UUID.randomUUID()).status)
        assertEquals(403, invite(user(role = UserRole.MANAGER)).status)
        assertEquals(409, invite(owner, email = foreign.email).status)
        val token = invitation["link"].asText().substringAfter("#token=")
        clock.now = clock.now.plusSeconds(72 * 3600)
        assertEquals(410, accept(token).status)
        assertEquals(410, post("/api/v1/auth/invitations/inspect", mapOf("token" to token)).status)
        val revoked = mvc.delete("/api/v1/companies/me/employees/${owner.id}/access") {
            header("Authorization", "Bearer ${jwt.issue(owner)}")
            header("Idempotency-Key", UUID.randomUUID())
        }.andReturn().response
        assertEquals(403, revoked.status)
        assertEquals(200, access(jwt.issue(owner)).status)
    }

    @Test
    fun `self revocation is forbidden even when another signer exists`() {
        val first = user()
        val second = user(first.companyId!!)
        val actors = listOf(first, second).map { verifier.parseClaims(jwt.issue(it)) }
        val pool = Executors.newFixedThreadPool(2)
        try {
            val results = pool.invokeAll(
                actors.map { actor ->
                    Callable {
                        try {
                            companies.revoke(actor, actor.userId, UUID.randomUUID())
                            200
                        } catch (e: ApiException) {
                            e.httpStatus.value()
                        }
                    }
                },
            ).map { it.get(10, TimeUnit.SECONDS) }.sorted()
            assertEquals(listOf(403, 403), results)
        } finally {
            pool.shutdownNow()
        }
        assertEquals(2, jdbc.queryForObject("select count(*) from users where company_id=? and status='ACTIVE' and can_sign", Int::class.java, first.companyId))
        val secondToken = jwt.issue(second)
        val revoked = mvc.delete("/api/v1/companies/me/employees/${second.id}/access") {
            header("Authorization", "Bearer ${jwt.issue(first)}")
            header("Idempotency-Key", UUID.randomUUID())
        }.andReturn().response
        assertEquals(200, revoked.status)
        assertEquals(401, access(secondToken).status)
        assertEquals(200, access(jwt.issue(first)).status)
    }

    @Test
    fun `identity audit forbids updates and deletes and contains no credentials`() {
        val user = user()
        assertEquals(200, post("/api/v1/auth/logout-all", token = jwt.issue(user)).status)
        val id = jdbc.queryForObject("select id from identity_audit where subject_id=? and action='SESSIONS_REVOKED'", UUID::class.java, user.id)!!
        assertFailsWith<DataAccessException> { jdbc.update("update identity_audit set action='ALTERED' where id=?", id) }
        assertFailsWith<DataAccessException> { jdbc.update("delete from identity_audit where id=?", id) }
        val json = jdbc.queryForObject("select row_to_json(a)::text from identity_audit a where id=?", String::class.java, id)!!
        assertFalse(json.contains("Secret2026"))
        assertFalse(json.contains("token"))
    }
}
