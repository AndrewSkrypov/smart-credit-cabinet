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
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import ru.creditcabinet.agent.domain.AgentAccessDecision
import ru.creditcabinet.agent.domain.AgentAccessPort
import ru.creditcabinet.application.domain.CompanyDirectory
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.assessment.domain.BankInfo
import ru.creditcabinet.common.exception.ApiException
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
class AgentNegativeAndBoundaryIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var verifier: JwtVerifier

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @MockitoBean
    private lateinit var companies: CompanyDirectory

    @MockitoBean
    private lateinit var agentAccess: AgentAccessPort

    @MockitoBean
    private lateinit var sources: CompanySources

    private val agentId = UUID.randomUUID()
    private val agent2Id = UUID.randomUUID()
    private val clientCompanyId = UUID.randomUUID()
    private val otherCompanyId = UUID.randomUUID()

    private val clientSnapshot = CompanySnapshot(clientCompanyId, "000100000014", "ИП Котова Граница", LegalForm.ENTREPRENEUR)
    private val otherSnapshot = CompanySnapshot(otherCompanyId, "000100000015", "ООО Чужая Компания", LegalForm.LEGAL_ENTITY)

    private val agentToken by lazy { token(Roles.AGENT, userId = agentId, fullName = "Агент Один") }
    private val agent2Token by lazy { token(Roles.AGENT, userId = agent2Id, fullName = "Агент Два") }
    private val clientToken by lazy { token(Roles.CLIENT, company = clientCompanyId, fullName = "Котова И.П.") }

    private val pdfBytes = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".toByteArray()
    private val createdApplicationIds = mutableListOf<UUID>()
    private var mandateActive = true
    private var identityDown = false

    @BeforeTest
    fun setUp() {
        // Agent channel is open to existing bank clients only.
        whenever(sources.bank(any())).thenReturn(BankInfo(true, null, emptyList()))
        mandateActive = true
        identityDown = false
        whenever(companies.find(any())).thenAnswer { inv ->
            when (inv.getArgument<UUID>(0)) {
                clientCompanyId -> clientSnapshot
                otherCompanyId -> otherSnapshot
                else -> null
            }
        }
        whenever(agentAccess.listCompanyIds(any())).thenAnswer { inv ->
            if (identityDown) throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Сервис полномочий агента временно недоступен")
            when (inv.getArgument<UUID>(0)) {
                agentId -> if (mandateActive) listOf(clientCompanyId) else emptyList()
                agent2Id -> listOf(otherCompanyId)
                else -> emptyList()
            }
        }
        whenever(agentAccess.checkAccess(any(), any())).thenAnswer { inv ->
            if (identityDown) throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Сервис полномочий агента временно недоступен")
            val reqAgent = inv.getArgument<UUID>(0)
            val reqCompany = inv.getArgument<UUID>(1)
            when {
                reqAgent == agentId && reqCompany == clientCompanyId && mandateActive ->
                    AgentAccessDecision(allowed = true, company = clientSnapshot)
                reqAgent == agent2Id && reqCompany == otherCompanyId ->
                    AgentAccessDecision(allowed = true, company = otherSnapshot)
                else -> AgentAccessDecision(allowed = false, company = null)
            }
        }
    }

    @AfterTest
    fun tearDown() {
        for (appId in createdApplicationIds) {
            jdbc.update("delete from pipeline_jobs where application_id = ?", appId)
            jdbc.update("delete from idempotency_keys where application_id = ?", appId)
        }
        createdApplicationIds.clear()
    }

    @Test
    fun `draft creation rejects invalid DTO values and blocks out-of-bounds product terms on confirmation`() {
        val dtoInvalidPayloads = listOf(
            draftBody(amount = 0L),
            draftBody(amount = -100_000L),
            draftBody(termMonths = 0),
            draftBody(revenue = -500_000L),
            draftBody(product = "UNKNOWN_PRODUCT"),
        )
        for (payload in dtoInvalidPayloads) {
            val response = mockMvc.post("/api/v1/agent/applications") {
                header("Authorization", "Bearer $agentToken")
                contentType = MediaType.APPLICATION_JSON
                content = payload
            }.andReturn().response
            assertTrue(
                response.status in listOf(400, 404, 422),
                "Expected 4xx for invalid DTO payload $payload, got ${response.status}: ${response.getContentAsString(Charsets.UTF_8)}",
            )
        }

        // Out-of-bounds product terms (below minAmount, above maxAmount, above maxTermMonths)
        // must report submissionProblems and block request-confirmation.
        val appId = createValidAgentDraft()
        val outOfBoundsUpdates = listOf(
            """{"productCode":"WORKING_CAPITAL","purpose":"WORKING_CAPITAL","amount":10000,"termMonths":12,"declaredRevenue12m":15000000,"declaredMonthlyDebtPayments":50000}""",
            """{"productCode":"WORKING_CAPITAL","purpose":"WORKING_CAPITAL","amount":500000000,"termMonths":12,"declaredRevenue12m":15000000,"declaredMonthlyDebtPayments":50000}""",
            """{"productCode":"WORKING_CAPITAL","purpose":"WORKING_CAPITAL","amount":1500000,"termMonths":120,"declaredRevenue12m":15000000,"declaredMonthlyDebtPayments":50000}""",
        )
        for (updatePayload in outOfBoundsUpdates) {
            val updateResp = mockMvc.put("/api/v1/agent/applications/$appId") {
                header("Authorization", "Bearer $agentToken")
                contentType = MediaType.APPLICATION_JSON
                content = updatePayload
            }.andReturn().response
            if (updateResp.status == 200) {
                val body = json(updateResp)
                assertTrue(
                    body["submissionProblems"].size() > 0,
                    "Expected non-empty submissionProblems for out-of-bounds payload $updatePayload",
                )
                val reqConfirm = mockMvc.post("/api/v1/agent/applications/$appId/request-confirmation") {
                    header("Authorization", "Bearer $agentToken")
                }.andReturn().response
                assertTrue(
                    reqConfirm.status in listOf(400, 409, 422),
                    "Expected 4xx on request-confirmation for out-of-bounds draft, got ${reqConfirm.status}",
                )
            } else {
                assertTrue(
                    updateResp.status in listOf(400, 422),
                    "Expected 200 with submissionProblems or 400/422, got ${updateResp.status}",
                )
            }
        }
    }

    @Test
    fun `client confirmation fails without bureau consent`() {
        val appId = createValidAgentDraft()

        val reqConfirm = mockMvc.post("/api/v1/agent/applications/$appId/request-confirmation") {
            header("Authorization", "Bearer $agentToken")
        }.andReturn().response
        assertEquals(200, reqConfirm.status, reqConfirm.getContentAsString(Charsets.UTF_8))

        val confirmWithoutConsent = mockMvc.post("/api/v1/applications/$appId/confirm") {
            header("Authorization", "Bearer $clientToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"bureauConsent":false}"""
        }.andReturn().response
        assertTrue(
            confirmWithoutConsent.status in listOf(400, 409),
            "Expected 400 or 409 without bureau consent, got ${confirmWithoutConsent.status}",
        )
    }

    @Test
    fun `agent documents and chat enforce strict IDOR isolation for foreign agent and revoked mandate`() {
        val appId = createValidAgentDraft()

        // Agent 1 uploads a document on DRAFT, then sends for confirmation and client confirms -> SUBMITTED
        val file = MockMultipartFile("file", "invoice.pdf", "application/pdf", pdfBytes)
        val uploadResp = mockMvc.multipart("/api/v1/agent/applications/$appId/documents") {
            file(file)
            param("type", "OTHER")
            header("Authorization", "Bearer $agentToken")
        }.andReturn().response
        assertEquals(201, uploadResp.status, uploadResp.getContentAsString(Charsets.UTF_8))

        assertEquals(
            200,
            mockMvc.post("/api/v1/agent/applications/$appId/request-confirmation") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )
        assertEquals(
            200,
            mockMvc.post("/api/v1/applications/$appId/confirm") {
                header("Authorization", "Bearer $clientToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"bureauConsent":true}"""
            }.andReturn().response.status,
        )

        val msgResp = mockMvc.post("/api/v1/agent/applications/$appId/messages") {
            header("Authorization", "Bearer $agentToken")
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType = MediaType.APPLICATION_JSON
            content = """{"text":"Уточнение по заявке от агента"}"""
        }.andReturn().response
        assertTrue(
            msgResp.status in listOf(200, 201),
            "Expected 200/201 for agent message, got ${msgResp.status}: ${msgResp.getContentAsString(Charsets.UTF_8)}",
        )

        // Foreign agent (agent2) must get 404 on documents and chat
        assertEquals(
            404,
            mockMvc.get("/api/v1/agent/applications/$appId/documents") {
                header("Authorization", "Bearer $agent2Token")
            }.andReturn().response.status,
        )
        assertEquals(
            404,
            mockMvc.multipart("/api/v1/agent/applications/$appId/documents") {
                file(MockMultipartFile("file", "stolen.pdf", "application/pdf", pdfBytes))
                param("type", "OTHER")
                header("Authorization", "Bearer $agent2Token")
            }.andReturn().response.status,
        )
        assertEquals(
            404,
            mockMvc.get("/api/v1/agent/applications/$appId/messages") {
                header("Authorization", "Bearer $agent2Token")
            }.andReturn().response.status,
        )
        assertEquals(
            404,
            mockMvc.post("/api/v1/agent/applications/$appId/messages") {
                header("Authorization", "Bearer $agent2Token")
                header("Idempotency-Key", UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"Чужое сообщение"}"""
            }.andReturn().response.status,
        )

        // After revoking mandate, even original agent1 must get 404 on documents and chat
        mandateActive = false
        assertEquals(
            404,
            mockMvc.get("/api/v1/agent/applications/$appId/documents") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )
        assertEquals(
            404,
            mockMvc.get("/api/v1/agent/applications/$appId/messages") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )
        assertEquals(
            404,
            mockMvc.post("/api/v1/agent/applications/$appId/messages") {
                header("Authorization", "Bearer $agentToken")
                header("Idempotency-Key", UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"После отзыва мандата"}"""
            }.andReturn().response.status,
        )
    }

    @Test
    fun `agent endpoints fail closed with 503 when identity mandate service is unavailable`() {
        identityDown = true

        assertEquals(
            503,
            mockMvc.get("/api/v1/agent/applications") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )
        assertEquals(
            503,
            mockMvc.post("/api/v1/agent/applications") {
                header("Authorization", "Bearer $agentToken")
                contentType = MediaType.APPLICATION_JSON
                content = draftBody()
            }.andReturn().response.status,
        )
    }

    @Test
    fun `agent cannot confirm application or access bank underwriting advice`() {
        val appId = createValidAgentDraft()

        assertEquals(
            403,
            mockMvc.post("/api/v1/applications/$appId/confirm") {
                header("Authorization", "Bearer $agentToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"bureauConsent":true}"""
            }.andReturn().response.status,
        )
        assertEquals(
            403,
            mockMvc.get("/api/v1/underwriting/applications/$appId/advice") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )
    }

    private fun createValidAgentDraft(): UUID {
        val resp = mockMvc.post("/api/v1/agent/applications") {
            header("Authorization", "Bearer $agentToken")
            contentType = MediaType.APPLICATION_JSON
            content = draftBody()
        }.andReturn().response
        assertEquals(201, resp.status, resp.getContentAsString(Charsets.UTF_8))
        val id = UUID.fromString(json(resp)["id"].asText())
        createdApplicationIds += id
        return id
    }

    private fun draftBody(
        product: String = "WORKING_CAPITAL",
        purpose: String = "WORKING_CAPITAL",
        amount: Long = 1_500_000L,
        termMonths: Int = 12,
        revenue: Long = 15_000_000L,
    ): String = """
        {
          "companyId": "$clientCompanyId",
          "productCode": "$product",
          "purpose": "$purpose",
          "amount": $amount,
          "termMonths": $termMonths,
          "declaredRevenue12m": $revenue,
          "declaredMonthlyDebtPayments": 50000
        }
    """.trimIndent()

    private fun json(response: MockHttpServletResponse): JsonNode =
        mapper.readTree(response.getContentAsString(Charsets.UTF_8))

    private fun token(
        role: String,
        userId: UUID = UUID.randomUUID(),
        company: UUID? = null,
        fullName: String = "Тест Пользователь",
    ): String = Jwts.builder()
        .subject(userId.toString())
        .claim(TokenClaims.ROLE, role)
        .claim(TokenClaims.FULL_NAME, fullName)
        .apply { company?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
        .claim(TokenClaims.CAN_SIGN, company != null)
        .expiration(Date(System.currentTimeMillis() + 3_600_000))
        .signWith(verifier.signingKey)
        .compact()
}
