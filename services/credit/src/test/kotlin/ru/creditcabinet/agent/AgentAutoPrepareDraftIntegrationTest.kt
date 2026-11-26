package ru.creditcabinet.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import ru.creditcabinet.agent.domain.AgentAccessDecision
import ru.creditcabinet.agent.domain.AgentAccessPort
import ru.creditcabinet.application.domain.CompanyDirectory
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Date
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
class AgentAutoPrepareDraftIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var companies: CompanyDirectory

    @MockitoBean private lateinit var agentAccess: AgentAccessPort

    private val agentId = UUID.randomUUID()
    private val foreignAgentId = UUID.randomUUID()
    private val companyId = UUID.randomUUID()
    private val snapshot = CompanySnapshot(companyId, "000100000099", "ООО Авточерновик", LegalForm.LEGAL_ENTITY)

    private val agentToken by lazy { token(Roles.AGENT, userId = agentId) }
    private val foreignAgentToken by lazy { token(Roles.AGENT, userId = foreignAgentId) }
    private val clientToken by lazy { token(Roles.CLIENT, company = companyId) }

    private val createdOfferIds = mutableListOf<UUID>()
    private val createdAppIds = mutableListOf<UUID>()
    private var identityDown = false

    @BeforeTest
    fun setUp() {
        identityDown = false
        whenever(companies.find(any())).thenAnswer { inv ->
            if (inv.getArgument<UUID>(0) == companyId) snapshot else null
        }
        whenever(agentAccess.listCompanyIds(any())).thenAnswer { inv ->
            if (identityDown) throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "identity unavailable")
            if (inv.getArgument<UUID>(0) == agentId) listOf(companyId) else emptyList()
        }
        whenever(agentAccess.checkAccess(any(), any())).thenAnswer { inv ->
            if (identityDown) throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "identity unavailable")
            if (inv.getArgument<UUID>(0) == agentId && inv.getArgument<UUID>(1) == companyId) {
                AgentAccessDecision(allowed = true, company = snapshot)
            } else {
                AgentAccessDecision(allowed = false, company = null)
            }
        }
    }

    @AfterTest
    fun tearDown() {
        for (offerId in createdOfferIds) {
            jdbc.update("update applications set pre_approved_offer_id = null where pre_approved_offer_id = ?", offerId)
            jdbc.update("delete from pre_approved_offers where id = ?", offerId)
        }
        createdOfferIds.clear()
        for (appId in createdAppIds) {
            jdbc.update("delete from pipeline_jobs where application_id = ?", appId)
        }
        createdAppIds.clear()
    }

    @Test
    fun `prepare-draft creates DRAFT requiring human confirmation, rejects duplicates, foreign agent, client and identity outage`() {
        val offerId = insertOffer()

        // Foreign agent -> 404
        assertEquals(
            404,
            mockMvc.post("/api/v1/agent/pre-approved-offers/$offerId/prepare-draft") {
                header("Authorization", "Bearer $foreignAgentToken")
            }.andReturn().response.status,
        )

        // Client -> 403
        assertEquals(
            403,
            mockMvc.post("/api/v1/agent/pre-approved-offers/$offerId/prepare-draft") {
                header("Authorization", "Bearer $clientToken")
            }.andReturn().response.status,
        )

        // Authorized agent -> 201 strictly in DRAFT with requiresHumanConfirmation = true and bureauConsent = false
        val resp = mockMvc.post("/api/v1/agent/pre-approved-offers/$offerId/prepare-draft") {
            header("Authorization", "Bearer $agentToken")
        }.andReturn().response
        assertEquals(201, resp.status, resp.getContentAsString(Charsets.UTF_8))
        val body = json(resp)
        assertTrue(body["requiresHumanConfirmation"].asBoolean())
        assertTrue(body["nextRequiredStep"].asText().contains("подтверждение клиенту"))
        val app = body["application"]
        createdAppIds += UUID.fromString(app["id"].asText())
        assertEquals("DRAFT", app["status"].asText())
        assertFalse(app["bureauConsent"].asBoolean())
        assertEquals(offerId.toString(), app["preApprovedOfferId"].asText())

        // Second attempt on same USED offer -> 409
        assertEquals(
            409,
            mockMvc.post("/api/v1/agent/pre-approved-offers/$offerId/prepare-draft") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )

        // Identity outage -> fail-closed 503
        identityDown = true
        assertEquals(
            503,
            mockMvc.post("/api/v1/agent/pre-approved-offers/$offerId/prepare-draft") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )
    }

    private fun insertOffer(): UUID {
        val id = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        jdbc.update(
            """
            insert into pre_approved_offers
                (id, company_id, product_code, max_amount, min_term_months, max_term_months, rate, valid_until, status, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            companyId,
            "OVERDRAFT",
            2_500_000L,
            6,
            12,
            BigDecimal("17.50"),
            Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)),
            "ACTIVE",
            now,
            now,
        )
        createdOfferIds += id
        return id
    }

    private fun json(response: MockHttpServletResponse): JsonNode =
        mapper.readTree(response.getContentAsString(Charsets.UTF_8))

    private fun token(role: String, userId: UUID = UUID.randomUUID(), company: UUID? = null): String =
        Jwts.builder()
            .subject(userId.toString())
            .claim(TokenClaims.ROLE, role)
            .claim(TokenClaims.FULL_NAME, "Тест Авточерновик")
            .apply { company?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
            .claim(TokenClaims.CAN_SIGN, company != null)
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(verifier.signingKey)
            .compact()
}
