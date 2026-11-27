package ru.creditcabinet.preapproved

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import ru.creditcabinet.agent.domain.AgentAccessDecision
import ru.creditcabinet.agent.domain.AgentAccessPort
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.assessment.domain.BankInfo
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.integration.domain.CompanySources
import java.util.Date
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
class PreApprovedGenerationIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var agentAccess: AgentAccessPort

    @MockitoBean private lateinit var sources: CompanySources

    private val companyId = UUID.randomUUID()
    private val agentId = UUID.randomUUID()
    private val managerToken by lazy { token(Roles.MANAGER) }
    private val clientToken by lazy { token(Roles.CLIENT, company = companyId) }
    private val agentToken by lazy { token(Roles.AGENT, userId = agentId) }
    private val createdOfferIds = mutableListOf<UUID>()

    @BeforeTest
    fun setUp() {
        whenever(sources.bank(any())).thenReturn(BankInfo(true, null, emptyList()))
        whenever(agentAccess.listCompanyIds(any())).thenAnswer { inv ->
            if (inv.getArgument<UUID>(0) == agentId) listOf(companyId) else emptyList()
        }
        whenever(agentAccess.checkAccess(any(), any())).thenAnswer { inv ->
            val allowed = inv.getArgument<UUID>(0) == agentId && inv.getArgument<UUID>(1) == companyId
            AgentAccessDecision(
                allowed = allowed,
                company = CompanySnapshot(companyId, "000100000098", "ООО Предодобрение", LegalForm.LEGAL_ENTITY).takeIf {
                    allowed
                },
            )
        }
    }

    @AfterTest
    fun tearDown() {
        for (offerId in createdOfferIds) {
            jdbc.update("delete from delivery_outbox where event_key = ?", "preapproved:$offerId")
            jdbc.update("delete from pre_approved_offers where id = ?", offerId)
        }
        createdOfferIds.clear()
    }

    @Test
    fun `bank staff generates pre-approved offer from turnover and bureau, notifies via outbox, and exposes to client and agent`() {
        val payload = """
            {
              "companyId": "$companyId",
              "productCode": "WORKING_CAPITAL",
              "annualRevenue": 18000000,
              "bureauWorstOverdueDays": 0,
              "existingMonthlyPayments": 50000,
              "validDays": 30
            }
        """.trimIndent()

        val resp = mockMvc.post("/api/v1/pre-approved-offers/generate") {
            header("Authorization", "Bearer $managerToken")
            contentType = MediaType.APPLICATION_JSON
            content = payload
        }.andReturn().response
        assertEquals(201, resp.status, resp.getContentAsString(Charsets.UTF_8))
        val offer = json(resp)
        val offerId = UUID.fromString(offer["id"].asText())
        createdOfferIds += offerId
        // 20% of 18M = 3.6M minus 600k = 3.0M
        assertEquals(3_000_000L, offer["maxAmount"].asLong())
        assertEquals("ACTIVE", offer["status"].asText())

        // Idempotent second call returns the same offer without creating a duplicate
        val secondResp = mockMvc.post("/api/v1/pre-approved-offers/generate") {
            header("Authorization", "Bearer $managerToken")
            contentType = MediaType.APPLICATION_JSON
            content = payload
        }.andReturn().response
        assertEquals(201, secondResp.status)
        assertEquals(offerId.toString(), json(secondResp)["id"].asText())

        // Proactive notification enqueued in durable outbox
        val outboxCount = jdbc.queryForObject(
            "select count(*) from delivery_outbox where channel = 'notification' and event_key = ?",
            Int::class.java,
            "preapproved:$offerId",
        )
        assertEquals(1, outboxCount)

        // Visible both to client and to authorized agent
        val clientList = json(
            mockMvc.get("/api/v1/pre-approved-offers") {
                header("Authorization", "Bearer $clientToken")
            }.andReturn().response,
        )
        assertTrue(clientList.any { it["id"].asText() == offerId.toString() })

        val agentResponse = mockMvc.get("/api/v1/agent/pre-approved-offers") {
            header("Authorization", "Bearer $agentToken")
        }.andReturn().response
        assertEquals(200, agentResponse.status, agentResponse.getContentAsString(Charsets.UTF_8))
        val agentList = json(agentResponse)
        assertTrue(agentList.any { it["id"].asText() == offerId.toString() })
    }

    @Test
    fun `ineligible bureau overdue or low revenue returns 204 and client or agent gets 403`() {
        val overdueResp = mockMvc.post("/api/v1/pre-approved-offers/generate") {
            header("Authorization", "Bearer $managerToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"companyId":"$companyId","productCode":"WORKING_CAPITAL","annualRevenue":20000000,"bureauWorstOverdueDays":15}"""
        }.andReturn().response
        assertEquals(204, overdueResp.status)

        val lowRevResp = mockMvc.post("/api/v1/pre-approved-offers/generate") {
            header("Authorization", "Bearer $managerToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"companyId":"$companyId","productCode":"WORKING_CAPITAL","annualRevenue":1000000,"bureauWorstOverdueDays":0}"""
        }.andReturn().response
        assertEquals(204, lowRevResp.status)

        val invalidResp = mockMvc.post("/api/v1/pre-approved-offers/generate") {
            header("Authorization", "Bearer $managerToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"companyId":"$companyId","productCode":"WORKING_CAPITAL","annualRevenue":-100,"bureauWorstOverdueDays":0}"""
        }.andReturn().response
        assertEquals(400, invalidResp.status)

        assertEquals(
            403,
            mockMvc.post("/api/v1/pre-approved-offers/generate") {
                header("Authorization", "Bearer $clientToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"companyId":"$companyId","productCode":"WORKING_CAPITAL","annualRevenue":20000000}"""
            }.andReturn().response.status,
        )
        assertEquals(
            403,
            mockMvc.post("/api/v1/pre-approved-offers/generate") {
                header("Authorization", "Bearer $agentToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"companyId":"$companyId","productCode":"WORKING_CAPITAL","annualRevenue":20000000}"""
            }.andReturn().response.status,
        )
    }

    private fun json(response: MockHttpServletResponse): JsonNode =
        mapper.readTree(response.getContentAsString(Charsets.UTF_8))

    private fun token(role: String, userId: UUID = UUID.randomUUID(), company: UUID? = null): String =
        Jwts.builder()
            .subject(userId.toString())
            .claim(TokenClaims.ROLE, role)
            .claim(TokenClaims.FULL_NAME, "Тест Предодобрения")
            .apply { company?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
            .claim(TokenClaims.CAN_SIGN, company != null)
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(verifier.signingKey)
            .compact()
}
