package ru.creditcabinet.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.http.Cookie
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.util.UriComponentsBuilder
import ru.creditcabinet.mcp.McpOAuthSettings.Companion.AUTHORIZE_PATH
import ru.creditcabinet.mcp.McpOAuthSettings.Companion.OPTIONS_PATH
import ru.creditcabinet.mcp.McpOAuthSettings.Companion.REVOCATION_PATH
import ru.creditcabinet.mcp.McpOAuthSettings.Companion.SCOPE
import ru.creditcabinet.mcp.McpOAuthSettings.Companion.TOKEN_PATH
import java.net.URLDecoder
import java.nio.charset.StandardCharsets.UTF_8
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@SpringBootTest(
    properties = [
        "cabinet.mcp-oauth.enabled=true",
        "cabinet.mcp-oauth.issuer=$ISSUER",
        "cabinet.mcp-oauth.client-id=$CLIENT",
        "cabinet.mcp-oauth.client-name=Test <Connector>",
        "cabinet.mcp-oauth.redirect-uri=$REDIRECT",
        "cabinet.mcp-oauth.identity-service-token=$IDENTITY_TOKEN",
        "cabinet.mcp-oauth.credit-service-token=$CREDIT_TOKEN",
    ],
)
@AutoConfigureMockMvc
class McpOAuthIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var database: JdbcTemplate

    @Autowired private lateinit var passwords: PasswordEncoder

    @Test
    fun `discovery documents exact issuer endpoints and S256 public client flow`() {
        mvc.get(OPTIONS_PATH).andExpect {
            status { isOk() }
            jsonPath("$.issuer") { value(ISSUER) }
            jsonPath("$.authorization_endpoint") { value(ISSUER + AUTHORIZE_PATH) }
            jsonPath("$.token_endpoint") { value(ISSUER + TOKEN_PATH) }
            jsonPath("$.revocation_endpoint") { value(ISSUER + REVOCATION_PATH) }
            jsonPath("$.code_challenge_methods_supported[0]") { value("S256") }
            jsonPath("$.grant_types_supported[0]") { value("authorization_code") }
            jsonPath("$.token_endpoint_auth_methods_supported[0]") { value("none") }
            jsonPath("$.scopes_supported[0]") { value(SCOPE) }
            jsonPath("$.registration_endpoint") { doesNotExist() }
        }
    }

    @Test
    fun `consent and PKCE produce an OAuth-only token and one-use Credit actor`() {
        val agent = account()
        val flow = begin()
        val approved = decide(flow, agent.email)
        assertEquals(303, approved.status)
        assertEquals(REDIRECT, assertNotNull(approved.redirectedUrl).substringBefore('?'))
        assertEquals(STATE, query(approved, "state"))
        assertEquals(ISSUER, query(approved, "iss"))
        assertEquals(400, decide(flow, agent.email).status)
        val code = assertNotNull(query(approved, "code"))
        assertEquals("invalid_grant", exchange(code, verifier = "x".repeat(43)).json("error"))
        assertEquals("invalid_target", exchange(code, resource = "$ISSUER/api/v1").json("error"))
        assertEquals("invalid_grant", exchange(code, redirect = "$REDIRECT/").json("error"))
        val tokenResponse = exchange(code)
        assertEquals(200, tokenResponse.status, tokenResponse.contentAsString)
        assertEquals("no-store", tokenResponse.getHeader(HttpHeaders.CACHE_CONTROL))
        assertEquals("Bearer", tokenResponse.json("token_type"))
        assertEquals(SCOPE, tokenResponse.json("scope"))
        assertNull(tokenResponse.json("refresh_token"))
        val access = assertNotNull(tokenResponse.json("access_token"))
        assertEquals("invalid_grant", exchange(code).json("error"))
        mvc.get("/api/v1/auth/session") { header(HttpHeaders.AUTHORIZATION, "Bearer $access") }
            .andExpect { status { isUnauthorized() } }
        val browserToken = mvc.post("/api/v1/auth/sign-in") {
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(mapOf("email" to agent.email, "password" to PASSWORD))
        }.andReturn().response.json("token")
        assertEquals(401, authorize(assertNotNull(browserToken)).status)
        assertEquals(401, authorize(access, CREDIT_TOKEN).status)
        assertEquals(401, authorize(access, null).status)
        val actor = authorize(access)
        assertEquals(200, actor.status, actor.contentAsString)
        assertEquals(agent.id.toString(), actor.json("actorId"))
        assertEquals("AGENT", actor.json("role"))
        assertEquals(RESOURCE, actor.json("resource"))
        assertEquals(SCOPE, actor.json("scope"))
        val assertion = assertNotNull(actor.json("actorAssertion"))
        assertEquals(401, redeem(assertion, IDENTITY_TOKEN).status)
        assertEquals(401, redeem(access).status)
        assertEquals(agent.id.toString(), redeem(assertion).json("actorId"))
        assertEquals(401, redeem(assertion).status)
    }

    @Test
    fun `reject unregistered callbacks and require exact resource and PKCE`() {
        val unregistered = open(redirect = "https://attacker.example/callback")
        assertEquals(400, unregistered.status)
        assertNull(unregistered.redirectedUrl)
        val differentClient = open(client = "elsewhere")
        assertEquals(400, differentClient.status)
        assertNull(differentClient.redirectedUrl)
        assertEquals("invalid_target", query(open(resource = "$RESOURCE/"), "error"))
        assertEquals("invalid_scope", query(open(scope = "$SCOPE credit:write"), "error"))
        assertEquals("invalid_request", query(open(method = "plain"), "error"))
        assertEquals("unsupported_response_type", query(open(responseType = "token"), "error"))
        val duplicate = open(duplicateResource = true)
        assertEquals("invalid_target", query(duplicate, "error"))
    }

    @Test
    fun `the first party consent form binds origin cookie and CSRF and supports explicit denial`() {
        val agent = account()
        val flow = begin()
        assertEquals(400, decide(flow, agent.email, origin = "https://attacker.example").status)
        assertEquals(400, decide(flow, agent.email, csrf = "x".repeat(43)).status)
        assertEquals(400, decide(flow, agent.email, sendCookie = false).status)
        assertEquals(401, decide(flow, agent.email, password = "not-the-password").status)
        val denied = decide(flow, decision = "deny")
        assertEquals(303, denied.status)
        assertEquals("access_denied", query(denied, "error"))
        assertEquals(STATE, query(denied, "state"))
        assertNull(query(denied, "code"))
        assertEquals(400, decide(flow, agent.email).status)
    }

    @Test
    fun `only an active AGENT is eligible and revocation invalidates pending assertions`() {
        val manager = account(role = "MANAGER")
        assertEquals(403, decide(begin(), manager.email).status)
        val blocked = account(status = "BLOCKED")
        assertEquals(403, decide(begin(), blocked.email).status)
        val agent = account()
        val access = accessFor(agent)
        val assertion = assertNotNull(authorize(access).json("actorAssertion"))
        assertEquals(200, revoke(access).status)
        assertEquals(200, revoke(access).status)
        assertEquals(401, authorize(access).status)
        assertEquals(401, redeem(assertion).status)
    }

    @Test
    fun `credential rotation role changes and expirations are checked on each handoff`() {
        val agent = account()
        val access = accessFor(agent)
        val assertion = assertNotNull(authorize(access).json("actorAssertion"))
        database.update("update users set credentials_version = credentials_version + 1 where id = ?", agent.id)
        assertEquals(401, authorize(access).status)
        assertEquals(401, redeem(assertion).status)

        val fresh = accessFor(agent)
        val expiringAssertion = assertNotNull(authorize(fresh).json("actorAssertion"))
        database.update("update mcp_oauth_assertions set expires_at = now() where assertion_hash = ?", digest(expiringAssertion))
        assertEquals(401, redeem(expiringAssertion).status)
        database.update("update mcp_oauth_access set expires_at = now() where token_hash = ?", digest(fresh))
        assertEquals(401, authorize(fresh).status)

        val freshAgain = accessFor(agent)
        database.update("update users set role = 'MANAGER' where id = ?", agent.id)
        assertEquals(401, authorize(freshAgain).status)
    }

    @Test
    fun `parallel requests cannot use an authorization code or Credit assertion twice`() {
        val code = assertNotNull(query(decide(begin(), account().email), "code"))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val exchanges = (1..2).map { executor.submit<MockHttpServletResponse> { exchange(code) } }
                .map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(listOf(200, 400), exchanges.map { it.status }.sorted())
            val access = assertNotNull(exchanges.single { it.status == 200 }.json("access_token"))
            val assertion = assertNotNull(authorize(access).json("actorAssertion"))
            val redemptions = (1..2).map { executor.submit<Int> { redeem(assertion).status } }
                .map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(listOf(200, 401), redemptions.sorted())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun open(
        redirect: String = REDIRECT,
        client: String = CLIENT,
        scope: String = SCOPE,
        resource: String = RESOURCE,
        method: String = "S256",
        responseType: String = "code",
        duplicateResource: Boolean = false,
    ) = mvc.get(AUTHORIZE_PATH) {
        param("response_type", responseType)
        param("client_id", client)
        param("redirect_uri", redirect)
        param("scope", scope)
        param("resource", resource)
        if (duplicateResource) param("resource", RESOURCE)
        param("state", STATE)
        param("code_challenge", pkceChallenge(VERIFIER))
        param("code_challenge_method", method)
    }.andReturn().response

    private fun begin(): Flow {
        val response = open()
        assertEquals(200, response.status, response.contentAsString)
        assertContains(assertNotNull(response.getHeader(HttpHeaders.SET_COOKIE)), "SameSite=Lax")
        assertContains(response.contentAsString, "Test &lt;Connector&gt;")
        val csrf = assertNotNull(Regex("name=\"csrf\" value=\"([A-Za-z0-9_-]+)\"").find(response.contentAsString)).groupValues[1]
        val cookie = assertNotNull(response.getCookie("__Host-cabinet-mcp-oauth"))
        assertEquals(true, cookie.isHttpOnly)
        assertEquals(true, cookie.secure)
        assertEquals("/", cookie.path)
        assertNull(cookie.domain)
        assertFalse(response.contentAsString.contains(cookie.value))
        return Flow(cookie, csrf)
    }

    private fun decide(
        flow: Flow,
        email: String = "",
        password: String = PASSWORD,
        decision: String = "approve",
        origin: String = ISSUER,
        csrf: String = flow.csrf,
        sendCookie: Boolean = true,
    ) = mvc.post(AUTHORIZE_PATH) {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        header(HttpHeaders.ORIGIN, origin)
        if (sendCookie) cookie(flow.cookie)
        param("csrf", csrf)
        param("decision", decision)
        if (decision == "approve") {
            param("email", email)
            param("password", password)
        }
    }.andReturn().response

    private fun exchange(
        code: String,
        verifier: String = VERIFIER,
        resource: String = RESOURCE,
        redirect: String = REDIRECT,
    ) = mvc.post(TOKEN_PATH) {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        param("grant_type", "authorization_code")
        param("client_id", CLIENT)
        param("redirect_uri", redirect)
        param("resource", resource)
        param("code", code)
        param("code_verifier", verifier)
    }.andReturn().response

    private fun authorize(access: String, serviceToken: String? = IDENTITY_TOKEN) = mvc.post("/internal/mcp/authorize") {
        contentType = MediaType.APPLICATION_JSON
        if (serviceToken != null) header(HttpHeaders.AUTHORIZATION, "Bearer $serviceToken")
        content = mapper.writeValueAsString(mapOf("accessToken" to access))
    }.andReturn().response

    private fun redeem(assertion: String, serviceToken: String = CREDIT_TOKEN) = mvc.post("/internal/mcp/redeem") {
        contentType = MediaType.APPLICATION_JSON
        header(HttpHeaders.AUTHORIZATION, "Bearer $serviceToken")
        content = mapper.writeValueAsString(mapOf("actorAssertion" to assertion))
    }.andReturn().response

    private fun revoke(access: String) = mvc.post(REVOCATION_PATH) {
        contentType = MediaType.APPLICATION_FORM_URLENCODED
        param("client_id", CLIENT)
        param("token", access)
    }.andReturn().response

    private fun account(role: String = "AGENT", status: String = "ACTIVE"): Account {
        val id = UUID.randomUUID()
        val email = "$id@example.test"
        database.update(
            "insert into users (id, full_name, email, role, status, password_hash) values (?, 'OAuth Test', ?, ?, ?, ?)",
            id,
            email,
            role,
            status,
            passwords.encode(PASSWORD),
        )
        return Account(id, email)
    }

    private fun accessFor(account: Account): String {
        val code = assertNotNull(query(decide(begin(), account.email), "code"))
        return assertNotNull(exchange(code).json("access_token"))
    }

    private fun MockHttpServletResponse.json(key: String): String? = mapper.readTree(contentAsString).get(key)?.asText()

    private fun query(response: MockHttpServletResponse, key: String): String? = response.redirectedUrl?.let {
        UriComponentsBuilder.fromUriString(it).build().queryParams.getFirst(key)?.let { value -> URLDecoder.decode(value, UTF_8) }
    }

    private data class Account(val id: UUID, val email: String)
    private data class Flow(val cookie: Cookie, val csrf: String)
}

private const val ISSUER = "https://cabinet.test"
private const val CLIENT = "mcp-integration"
private const val REDIRECT = "http://127.0.0.1:49191/callback"
private const val RESOURCE = "$ISSUER/api/mcp"
private const val IDENTITY_TOKEN = "identity-only-synthetic-service-token"
private const val CREDIT_TOKEN = "credit-only-synthetic-service-token"
private const val VERIFIER = "KYZ7wFAiINXV8C4spDDq8ayj4PTTM1QqCpGP7-_oAcH"
private const val PASSWORD = "McpIntegration2026"
private const val STATE = "oauth-callback-state"
