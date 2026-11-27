package ru.creditcabinet.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import org.mockito.kotlin.any
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.agent.domain.AgentAccessDecision
import ru.creditcabinet.agent.domain.AgentAccessPort
import ru.creditcabinet.agent.infrastructure.IdentityAgentAccessClient
import ru.creditcabinet.application.domain.Actor
import ru.creditcabinet.application.domain.ApplicationEvent
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.CommissionStatus
import ru.creditcabinet.application.domain.CompanyDirectory
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.application.service.TransitionNotices
import ru.creditcabinet.application.service.Transitions
import ru.creditcabinet.assessment.domain.BankInfo
import ru.creditcabinet.common.notifications.PlatformNotification
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.integration.CompanySources
import ru.creditcabinet.integration.SourceUnavailableException
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
class AgentWorkflowIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var verifier: JwtVerifier

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var applications: ApplicationRepository

    @Autowired
    private lateinit var transitions: Transitions

    @Autowired
    private lateinit var tx: TransactionTemplate

    @MockitoBean
    private lateinit var companies: CompanyDirectory

    @MockitoBean
    private lateinit var agentAccess: AgentAccessPort

    @MockitoBean
    private lateinit var sources: CompanySources

    private val agentId = UUID.randomUUID()
    private val agent2Id = UUID.randomUUID()
    private val kotovaCompanyId = UUID.randomUUID()
    private val stroygradCompanyId = UUID.randomUUID()
    private val agent2CompanyId = UUID.randomUUID()

    private val kotovaSnapshot = CompanySnapshot(kotovaCompanyId, "000100000004", "ИП Котова", LegalForm.ENTREPRENEUR)
    private val stroygradSnapshot = CompanySnapshot(stroygradCompanyId, "000100000005", "ООО СтройГрад", LegalForm.LEGAL_ENTITY)
    private val agent2CompanySnapshot = CompanySnapshot(agent2CompanyId, "000100000006", "ООО Северная мебель", LegalForm.LEGAL_ENTITY)

    private val agentToken by lazy { token(Roles.AGENT, userId = agentId, fullName = "Агент Демо") }
    private val agent2Token by lazy { token(Roles.AGENT, userId = agent2Id, fullName = "Агент Два") }
    private val kotovaClientToken by lazy { token(Roles.CLIENT, company = kotovaCompanyId, fullName = "Котова И.П.") }
    private val stroygradClientToken by lazy { token(Roles.CLIENT, company = stroygradCompanyId, fullName = "Директор СтройГрад") }

    private val pdfBytes = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".toByteArray()
    private val createdApplicationIds = mutableListOf<UUID>()
    private val createdOfferIds = mutableListOf<UUID>()
    private var kotovaMandateActive = true

    @BeforeTest
    fun setUp() {
        kotovaMandateActive = true
        whenever(sources.bank(any())).thenReturn(BankInfo(true, null, emptyList()))
        whenever(companies.find(any())).thenAnswer { inv ->
            when (inv.getArgument<UUID>(0)) {
                kotovaCompanyId -> kotovaSnapshot
                stroygradCompanyId -> stroygradSnapshot
                agent2CompanyId -> agent2CompanySnapshot
                else -> null
            }
        }
        whenever(agentAccess.listCompanyIds(any())).thenAnswer { inv ->
            when (inv.getArgument<UUID>(0)) {
                agentId -> if (kotovaMandateActive) listOf(kotovaCompanyId) else emptyList()
                agent2Id -> listOf(agent2CompanyId)
                else -> emptyList()
            }
        }
        whenever(agentAccess.checkAccess(any(), any())).thenAnswer { inv ->
            val reqAgent = inv.getArgument<UUID>(0)
            val reqCompany = inv.getArgument<UUID>(1)
            when {
                reqAgent == agentId && reqCompany == kotovaCompanyId && kotovaMandateActive ->
                    AgentAccessDecision(allowed = true, company = kotovaSnapshot)
                reqAgent == agent2Id && reqCompany == agent2CompanyId ->
                    AgentAccessDecision(allowed = true, company = agent2CompanySnapshot)
                else -> AgentAccessDecision(allowed = false, company = null)
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
        for (appId in createdApplicationIds) {
            jdbc.update("delete from pipeline_jobs where application_id = ?", appId)
            jdbc.update("delete from idempotency_keys where application_id = ?", appId)
        }
        createdApplicationIds.clear()
    }

    private fun token(role: String, company: UUID? = null, userId: UUID = UUID.randomUUID(), fullName: String = "Тест"): String =
        Jwts.builder()
            .subject(userId.toString())
            .claim(TokenClaims.ROLE, role)
            .claim(TokenClaims.FULL_NAME, fullName)
            .apply { company?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
            .claim(TokenClaims.CAN_SIGN, company != null)
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(verifier.signingKey)
            .compact()

    private fun json(response: MockHttpServletResponse): JsonNode =
        mapper.readTree(response.getContentAsString(Charsets.UTF_8))

    @Test
    fun `agent notification access checks owner mandate and bank eligibility`() {
        val draft = createAgentDraft()
        assertEquals(201, draft.status)
        val id = json(draft)["id"].asText()
        fun access(token: String): Int = mockMvc.get("/api/v1/agent/applications/$id/notification-access") {
            header("Authorization", "Bearer $token")
        }.andReturn().response.status
        assertEquals(204, access(agentToken))
        assertEquals(404, access(agent2Token))
        whenever(sources.bank(kotovaSnapshot.inn)).thenReturn(BankInfo(false, null, emptyList()))
        assertEquals(404, access(agentToken))
        whenever(sources.bank(kotovaSnapshot.inn)).thenThrow(SourceUnavailableException("Данные банка", "Синтетический отказ"))
        assertEquals(503, access(agentToken))
        kotovaMandateActive = false
        assertEquals(404, access(agentToken))
    }

    @Test
    fun `agent creation and pre-approved draft require active bank client`() {
        whenever(sources.bank(kotovaSnapshot.inn)).thenReturn(BankInfo(false, null, emptyList()))
        assertEquals(409, createAgentDraft().status)
        val offerId = insertOffer(kotovaCompanyId)
        assertEquals(
            409,
            mockMvc.post("/api/v1/agent/pre-approved-offers/$offerId/apply") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )
        assertEquals("ACTIVE", jdbc.queryForObject("select status from pre_approved_offers where id = ?", String::class.java, offerId))
    }

    private fun createAgentDraft(
        companyId: UUID = kotovaCompanyId,
        token: String = agentToken,
        product: String = "WORKING_CAPITAL",
        purpose: String = "WORKING_CAPITAL",
        amount: Long = 2_000_000L,
        revenue: Long? = 24_000_000L,
    ): MockHttpServletResponse {
        val revPart = if (revenue != null) ""","declaredRevenue12m":$revenue,"declaredMonthlyDebtPayments":50000""" else ""
        val resp = mockMvc.post("/api/v1/agent/applications") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """{"companyId":"$companyId","productCode":"$product","purpose":"$purpose","amount":$amount,"termMonths":12$revPart}"""
        }.andReturn().response
        if (resp.status == 201) {
            createdApplicationIds += UUID.fromString(json(resp)["id"].asText())
        }
        return resp
    }

    @Test
    fun `full agent to client confirmation to submitted flow with document upload and notifications`() {
        val createResp = createAgentDraft(revenue = null)
        assertEquals(201, createResp.status, createResp.getContentAsString(Charsets.UTF_8))
        val draft = json(createResp)
        val appId = UUID.fromString(draft["id"].asText())
        assertEquals("DRAFT", draft["status"].asText())
        assertEquals(agentId.toString(), draft["agentId"].asText())
        assertEquals("EXPECTED", draft["agentCommissionStatus"].asText())
        assertTrue(draft["agentCommissionAmount"].asLong() > 0)

        // Agent's private draft is hidden from the client until sent for confirmation
        val hiddenFromClient = mockMvc.get("/api/v1/applications/$appId") {
            header("Authorization", "Bearer $kotovaClientToken")
        }.andReturn().response
        assertEquals(404, hiddenFromClient.status)

        // Update draft with revenue (bureauConsent remains false for agent)
        val updateResp = mockMvc.put("/api/v1/agent/applications/$appId") {
            header("Authorization", "Bearer $agentToken")
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"productCode":"WORKING_CAPITAL","purpose":"WORKING_CAPITAL","amount":2000000,"termMonths":12,"declaredRevenue12m":24000000,"declaredMonthlyDebtPayments":50000}"""
        }.andReturn().response
        assertEquals(200, updateResp.status, updateResp.getContentAsString(Charsets.UTF_8))
        assertFalse(json(updateResp)["bureauConsent"].asBoolean())
        assertEquals(0, json(updateResp)["submissionProblems"].size(), json(updateResp)["submissionProblems"].toString())

        val agentGetResp = mockMvc.get("/api/v1/agent/applications/$appId") {
            header("Authorization", "Bearer $agentToken")
        }.andReturn().response
        assertEquals(200, agentGetResp.status, agentGetResp.getContentAsString(Charsets.UTF_8))
        assertEquals(0, json(agentGetResp)["submissionProblems"].size(), json(agentGetResp)["submissionProblems"].toString())

        // Upload document as agent
        val uploadResp = mockMvc.multipart("/api/v1/agent/applications/$appId/documents") {
            header("Authorization", "Bearer $agentToken")
            param("type", "TAX_RETURN")
            file(MockMultipartFile("file", "tax.pdf", "application/pdf", pdfBytes))
        }.andReturn().response
        assertEquals(201, uploadResp.status, uploadResp.getContentAsString(Charsets.UTF_8))
        val docId = UUID.fromString(json(uploadResp)["id"].asText())

        // Download document as agent — verifies single checkAccess call (no duplicate identity lookup)
        clearInvocations(agentAccess)
        val downloadResp = mockMvc.get("/api/v1/agent/applications/$appId/documents/$docId") {
            header("Authorization", "Bearer $agentToken")
        }.andReturn().response
        assertEquals(200, downloadResp.status)
        assertEquals(pdfBytes.size, downloadResp.contentAsByteArray.size)
        verify(agentAccess, times(1)).checkAccess(agentId, kotovaCompanyId)

        // Request client confirmation
        val reqConfResp = mockMvc.post("/api/v1/agent/applications/$appId/request-confirmation") {
            header("Authorization", "Bearer $agentToken")
        }.andReturn().response
        assertEquals(200, reqConfResp.status, reqConfResp.getContentAsString(Charsets.UTF_8))
        assertEquals("PENDING_CLIENT_CONFIRMATION", json(reqConfResp)["status"].asText())

        // Client now sees application in PENDING_CLIENT_CONFIRMATION
        val clientGet = mockMvc.get("/api/v1/applications/$appId") {
            header("Authorization", "Bearer $kotovaClientToken")
        }.andReturn().response
        assertEquals(200, clientGet.status, clientGet.getContentAsString(Charsets.UTF_8))
        assertEquals("PENDING_CLIENT_CONFIRMATION", json(clientGet)["status"].asText())

        // Client confirms with bureauConsent = true -> SUBMITTED
        val confirmResp = mockMvc.post("/api/v1/applications/$appId/confirm") {
            header("Authorization", "Bearer $kotovaClientToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"bureauConsent":true}"""
        }.andReturn().response
        assertEquals(200, confirmResp.status, confirmResp.getContentAsString(Charsets.UTF_8))
        assertEquals("SUBMITTED", json(confirmResp)["status"].asText())
        assertTrue(json(confirmResp)["bureauConsent"].asBoolean())
        assertEquals(
            1,
            jdbc.queryForObject(
                "select count(*) from pipeline_jobs where application_id = ? and step = 'DATA_CHECK'",
                Int::class.java,
                appId,
            ),
        )

        // Agent can read and post in chat once submitted — single checkAccess call per request
        clearInvocations(agentAccess)
        val chatPost = mockMvc.post("/api/v1/agent/applications/$appId/messages") {
            header("Authorization", "Bearer $agentToken")
            header("Idempotency-Key", UUID.randomUUID().toString())
            contentType = MediaType.APPLICATION_JSON
            content = """{"text":"Добрый день, уточните статус заявки"}"""
        }.andReturn().response
        assertEquals(200, chatPost.status, chatPost.getContentAsString(Charsets.UTF_8))
        assertEquals("COMPANY", json(chatPost)["side"].asText())
        verify(agentAccess, times(1)).checkAccess(agentId, kotovaCompanyId)

        clearInvocations(agentAccess)
        val chatGet = mockMvc.get("/api/v1/agent/applications/$appId/messages") {
            header("Authorization", "Bearer $agentToken")
        }.andReturn().response
        assertEquals(200, chatGet.status)
        assertEquals(1, json(chatGet)["messages"].size())
        verify(agentAccess, times(1)).checkAccess(agentId, kotovaCompanyId)
    }

    @Test
    fun `agent cannot create or open applications for foreign companies or other agents`() {
        // Creating for stroygrad (unassigned) -> 404
        assertEquals(404, createAgentDraft(companyId = stroygradCompanyId).status)
        // Creating for agent2's company -> 404
        assertEquals(404, createAgentDraft(companyId = agent2CompanyId).status)

        // agent2 creates draft for their own company
        val agent2Draft = createAgentDraft(companyId = agent2CompanyId, token = agent2Token)
        assertEquals(201, agent2Draft.status)
        val agent2AppId = UUID.fromString(json(agent2Draft)["id"].asText())

        // agent1 cannot access agent2's application -> 404
        assertEquals(
            404,
            mockMvc.get("/api/v1/agent/applications/$agent2AppId") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )
        assertEquals(
            404,
            mockMvc.get("/api/v1/applications/$agent2AppId") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )
    }

    @Test
    fun `revoked mandate mid-flight returns 404 on all agent requests to application, documents, and chat`() {
        val createResp = createAgentDraft()
        assertEquals(201, createResp.status)
        val appId = UUID.fromString(json(createResp)["id"].asText())

        val uploadResp = mockMvc.multipart("/api/v1/agent/applications/$appId/documents") {
            header("Authorization", "Bearer $agentToken")
            param("type", "TAX_RETURN")
            file(MockMultipartFile("file", "tax.pdf", "application/pdf", pdfBytes))
        }.andReturn().response
        assertEquals(201, uploadResp.status)
        val docId = UUID.fromString(json(uploadResp)["id"].asText())

        assertEquals(
            200,
            mockMvc.post("/api/v1/agent/applications/$appId/request-confirmation") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )
        assertEquals(
            200,
            mockMvc.post("/api/v1/applications/$appId/confirm") {
                header("Authorization", "Bearer $kotovaClientToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"bureauConsent":true}"""
            }.andReturn().response.status,
        )

        // Revoke mandate mid-flight
        kotovaMandateActive = false

        // All subsequent agent requests MUST return 404
        fun getAsAgent(url: String) = mockMvc.get(url) { header("Authorization", "Bearer $agentToken") }.andReturn().response.status
        assertEquals(404, getAsAgent("/api/v1/agent/applications/$appId"))
        assertEquals(404, getAsAgent("/api/v1/applications/$appId"))
        assertEquals(404, getAsAgent("/api/v1/agent/applications/$appId/documents"))
        assertEquals(404, getAsAgent("/api/v1/agent/applications/$appId/documents/$docId"))
        assertEquals(404, getAsAgent("/api/v1/applications/$appId/documents/$docId"))
        assertEquals(
            404,
            mockMvc.multipart("/api/v1/agent/applications/$appId/documents") {
                header("Authorization", "Bearer $agentToken")
                param("type", "TAX_RETURN")
                file(MockMultipartFile("file", "tax2.pdf", "application/pdf", pdfBytes))
            }.andReturn().response.status,
        )
        assertEquals(
            404,
            mockMvc.delete("/api/v1/agent/applications/$appId/documents/$docId") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )
        assertEquals(404, getAsAgent("/api/v1/agent/applications/$appId/messages"))
        assertEquals(
            404,
            mockMvc.post("/api/v1/agent/applications/$appId/messages") {
                header("Authorization", "Bearer $agentToken")
                header("Idempotency-Key", UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"Попытка после отзыва мандата"}"""
            }.andReturn().response.status,
        )
        assertEquals(404, getAsAgent("/api/v1/applications/$appId/messages"))

        // Application also disappears from agent's list and portfolio
        val listResp = mockMvc.get("/api/v1/agent/applications") { header("Authorization", "Bearer $agentToken") }.andReturn().response
        assertEquals(200, listResp.status)
        assertEquals(0, json(listResp).size())
    }

    @Test
    fun `direct client submit on agent application returns 409 and agent confirm or offer or sign returns 403`() {
        val createResp = createAgentDraft()
        assertEquals(201, createResp.status)
        val appId = UUID.fromString(json(createResp)["id"].asText())

        assertEquals(
            200,
            mockMvc.post("/api/v1/agent/applications/$appId/request-confirmation") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )

        // Review Focus #2: client attempting direct /submit on an agent-owned application -> 409
        val directSubmit = mockMvc.post("/api/v1/applications/$appId/submit") {
            header("Authorization", "Bearer $kotovaClientToken")
        }.andReturn().response
        assertEquals(409, directSubmit.status, directSubmit.getContentAsString(Charsets.UTF_8))

        // Review Focus #2: AGENT attempting confirm, offer/accept, offer/decline, sign -> 403
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
            mockMvc.post("/api/v1/applications/$appId/offer/accept") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )
        assertEquals(
            403,
            mockMvc.post("/api/v1/applications/$appId/offer/decline") {
                header("Authorization", "Bearer $agentToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"comment":"нет"}"""
            }.andReturn().response.status,
        )
        assertEquals(
            403,
            mockMvc.post("/api/v1/applications/$appId/sign") {
                header("Authorization", "Bearer $agentToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"code":"123456"}"""
            }.andReturn().response.status,
        )
    }

    @Test
    fun `client can return application to agent and agent can cancel with commission cancelled`() {
        val createResp = createAgentDraft()
        assertEquals(201, createResp.status)
        val appId = UUID.fromString(json(createResp)["id"].asText())

        assertEquals(
            200,
            mockMvc.post("/api/v1/agent/applications/$appId/request-confirmation") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )

        val returnResp = mockMvc.post("/api/v1/applications/$appId/return-to-agent") {
            header("Authorization", "Bearer $kotovaClientToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"comment":"Уменьшите сумму до 1.5 млн"}"""
        }.andReturn().response
        assertEquals(200, returnResp.status, returnResp.getContentAsString(Charsets.UTF_8))
        assertEquals("DRAFT", json(returnResp)["status"].asText())

        // The first handoff keeps the application and return history visible to its own client.
        val clientRead = mockMvc.get("/api/v1/applications/$appId") {
            header("Authorization", "Bearer $kotovaClientToken")
        }.andReturn().response
        assertEquals(200, clientRead.status)
        val agentHistory = mockMvc.get("/api/v1/agent/applications/$appId/events") {
            header("Authorization", "Bearer $agentToken")
        }.andReturn().response
        assertEquals(200, agentHistory.status)
        assertTrue(json(agentHistory).any { it["event"].asText() == "RETURN_TO_AGENT" && it["comment"].asText().contains("1.5") })

        // The agent still owns changes to the returned draft while the mandate is active.
        assertEquals(
            404,
            mockMvc.put("/api/v1/applications/$appId") {
                header("Authorization", "Bearer $kotovaClientToken")
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"productCode":"WORKING_CAPITAL","purpose":"WORKING_CAPITAL","amount":1500000,"termMonths":12,"declaredRevenue12m":24000000,"bureauConsent":true}"""
            }.andReturn().response.status,
        )
        assertEquals(
            404,
            mockMvc.post("/api/v1/applications/$appId/withdraw") {
                header("Authorization", "Bearer $kotovaClientToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"comment":"Попытка отозвать чужой черновик"}"""
            }.andReturn().response.status,
        )
        assertEquals(
            404,
            mockMvc.multipart("/api/v1/applications/$appId/documents") {
                header("Authorization", "Bearer $kotovaClientToken")
                param("type", "TAX_RETURN")
                file(MockMultipartFile("file", "tax.pdf", "application/pdf", pdfBytes))
            }.andReturn().response.status,
        )

        val cancelResp = mockMvc.post("/api/v1/agent/applications/$appId/cancel") {
            header("Authorization", "Bearer $agentToken")
        }.andReturn().response
        assertEquals(200, cancelResp.status, cancelResp.getContentAsString(Charsets.UTF_8))
        assertEquals("WITHDRAWN", json(cancelResp)["status"].asText())
        assertEquals("CANCELLED", json(cancelResp)["agentCommissionStatus"].asText())
    }

    @Test
    fun `commission transitions to ACCRUED on DISBURSED and CANCELLED on REJECTED`() {
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)

        // 1) DISBURSED -> ACCRUED
        val create1 = createAgentDraft(product = "WORKING_CAPITAL", amount = 2_000_000L)
        assertEquals(201, create1.status)
        val app1Id = UUID.fromString(json(create1)["id"].asText())
        val app1 = assertNotNull(applications.find(app1Id))
        val rateBps = assertNotNull(app1.agentCommissionRateBps)

        val disbursing = applications.save(app1.copy(status = ApplicationStatus.DISBURSING))
        val disbursed = tx.execute { transitions.commit(disbursing.move(ApplicationEvent.DISBURSED, Actor.SYSTEM, now)) }!!
        assertEquals(ApplicationStatus.DISBURSED, disbursed.status)
        assertEquals(CommissionStatus.ACCRUED, disbursed.agentCommissionStatus)
        assertEquals(2_000_000L * rateBps / 10_000L, disbursed.agentCommissionAmount)

        // Portfolio reflects accrued commission
        val portfolioResp = mockMvc.get("/api/v1/agent/portfolio") {
            header("Authorization", "Bearer $agentToken")
        }.andReturn().response
        assertEquals(200, portfolioResp.status, portfolioResp.getContentAsString(Charsets.UTF_8))
        val portfolio = json(portfolioResp)
        assertEquals(1, portfolio["assignedCompaniesCount"].asInt())
        assertEquals(1, portfolio["disbursedCount"].asInt())
        assertEquals(2_000_000L * rateBps / 10_000L, portfolio["accruedCommissionTotal"].asLong())

        // 2) REJECTED -> CANCELLED
        val create2 = createAgentDraft(product = "INVESTMENT", purpose = "EQUIPMENT", amount = 3_000_000L)
        assertEquals(201, create2.status)
        val app2Id = UUID.fromString(json(create2)["id"].asText())
        val app2 = assertNotNull(applications.find(app2Id))
        val scoring = applications.save(app2.copy(status = ApplicationStatus.SCORING))
        val rejected = tx.execute { transitions.commit(scoring.move(ApplicationEvent.AUTO_REJECT, Actor.SYSTEM, now, reasonCode = "SF_STATUS")) }!!
        assertEquals(ApplicationStatus.REJECTED, rejected.status)
        assertEquals(CommissionStatus.CANCELLED, rejected.agentCommissionStatus)

        // TransitionNotices emits client notice on REQUEST_CLIENT_CONFIRMATION and agent notice on DISBURSED/REJECT
        val reqEntry = app1.move(ApplicationEvent.REQUEST_CLIENT_CONFIRMATION, Actor(ru.creditcabinet.application.domain.ActorType.AGENT, agentId), now).entry
        val reqNotices = TransitionNotices.of(app1, reqEntry, "Кредит")
        assertTrue(reqNotices.any { it.recipientCompanyId == kotovaCompanyId && it.type == PlatformNotification.ACTION_REQUIRED })

        val disbursedEntry = disbursing.move(ApplicationEvent.DISBURSED, Actor.SYSTEM, now).entry
        val disbursedNotices = TransitionNotices.of(disbursed, disbursedEntry, "Кредит")
        assertTrue(disbursedNotices.any { it.recipientId == agentId })
        assertTrue(disbursedNotices.any { it.recipientCompanyId == kotovaCompanyId })
    }

    @Test
    fun `agent can list and apply pre-approved offers only for assigned companies`() {
        val ownOfferId = insertOffer(kotovaCompanyId)
        val foreignOfferId = insertOffer(stroygradCompanyId)

        val listResp = mockMvc.get("/api/v1/agent/pre-approved-offers") {
            header("Authorization", "Bearer $agentToken")
        }.andReturn().response
        assertEquals(200, listResp.status, listResp.getContentAsString(Charsets.UTF_8))
        val ids = json(listResp).map { it["id"].asText() }
        assertTrue(ownOfferId.toString() in ids)
        assertFalse(foreignOfferId.toString() in ids)

        val foreignApply = mockMvc.post("/api/v1/agent/pre-approved-offers/$foreignOfferId/apply") {
            header("Authorization", "Bearer $agentToken")
        }.andReturn().response
        assertEquals(404, foreignApply.status)

        val applyResp = mockMvc.post("/api/v1/agent/pre-approved-offers/$ownOfferId/apply") {
            header("Authorization", "Bearer $agentToken")
        }.andReturn().response
        assertEquals(201, applyResp.status, applyResp.getContentAsString(Charsets.UTF_8))
        val appId = UUID.fromString(json(applyResp)["id"].asText())
        createdApplicationIds += appId
        assertEquals(ownOfferId.toString(), json(applyResp)["preApprovedOfferId"].asText())
        assertEquals(agentId.toString(), json(applyResp)["agentId"].asText())

        // Cancelling an unsubmitted agent draft releases the pre-approved offer back to ACTIVE
        val cancelResp = mockMvc.post("/api/v1/agent/applications/$appId/cancel") {
            header("Authorization", "Bearer $agentToken")
        }.andReturn().response
        assertEquals(200, cancelResp.status, cancelResp.getContentAsString(Charsets.UTF_8))
        assertEquals(
            "ACTIVE",
            jdbc.queryForObject("select status from pre_approved_offers where id = ?", String::class.java, ownOfferId),
        )

        // Re-apply, send to client for confirmation, then client withdraws -> offer also returns to ACTIVE
        val reapplyResp = mockMvc.post("/api/v1/agent/pre-approved-offers/$ownOfferId/apply") {
            header("Authorization", "Bearer $agentToken")
        }.andReturn().response
        assertEquals(201, reapplyResp.status, reapplyResp.getContentAsString(Charsets.UTF_8))
        val app2Id = UUID.fromString(json(reapplyResp)["id"].asText())
        createdApplicationIds += app2Id
        assertEquals(
            200,
            mockMvc.put("/api/v1/agent/applications/$app2Id") {
                header("Authorization", "Bearer $agentToken")
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"productCode":"OVERDRAFT","purpose":"CASH_GAP","amount":2000000,"termMonths":12,"declaredRevenue12m":24000000}"""
            }.andReturn().response.status,
        )
        assertEquals(
            200,
            mockMvc.post("/api/v1/agent/applications/$app2Id/request-confirmation") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )
        val withdrawResp = mockMvc.post("/api/v1/applications/$app2Id/withdraw") {
            header("Authorization", "Bearer $kotovaClientToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"comment":"Передумали"}"""
        }.andReturn().response
        assertEquals(200, withdrawResp.status, withdrawResp.getContentAsString(Charsets.UTF_8))
        assertEquals(
            "ACTIVE",
            jdbc.queryForObject("select status from pre_approved_offers where id = ?", String::class.java, ownOfferId),
        )
    }

    @Test
    fun `product version bump in DRAFT or PENDING_CLIENT_CONFIRMATION does not deadlock client confirmation`() {
        val riskToken = token(Roles.RISK_ADMIN, fullName = "Риск Админ")
        val initialProduct = json(
            mockMvc.get("/api/v1/bank/products/WORKING_CAPITAL") {
                header("Authorization", "Bearer $riskToken")
            }.andReturn().response,
        )[0]["product"]

        fun bumpProduct(expectedVersion: Long, baseRate: String, commissionBps: Int): Long {
            val payload = mapOf(
                "expectedVersion" to expectedVersion,
                "name" to initialProduct["name"].asText(),
                "description" to initialProduct["description"].asText(),
                "purposes" to initialProduct["purposes"].map { it.asText() },
                "minAmount" to initialProduct["minAmount"].asLong(),
                "maxAmount" to initialProduct["maxAmount"].asLong(),
                "minTermMonths" to initialProduct["minTermMonths"].asInt(),
                "maxTermMonths" to initialProduct["maxTermMonths"].asInt(),
                "baseRate" to BigDecimal(baseRate),
                "autoApprovalLimit" to initialProduct["autoApprovalLimit"].asLong(),
                "revolving" to initialProduct["revolving"].asBoolean(),
                "active" to true,
                "agentCommissionRateBps" to commissionBps,
                "reason" to "Тестовое обновление ставки",
            )
            val resp = mockMvc.put("/api/v1/bank/products/WORKING_CAPITAL") {
                header("Authorization", "Bearer $riskToken")
                contentType = MediaType.APPLICATION_JSON
                content = mapper.writeValueAsString(payload)
            }.andReturn().response
            assertEquals(200, resp.status, resp.getContentAsString(Charsets.UTF_8))
            return json(resp)["product"]["version"].asLong()
        }

        var currentVersion = initialProduct["version"].asLong()
        try {
            // 1) Create draft on version N, then bump product to N+1 while still in DRAFT
            val createResp = createAgentDraft(product = "WORKING_CAPITAL", amount = 2_000_000L)
            assertEquals(201, createResp.status)
            val appId = UUID.fromString(json(createResp)["id"].asText())
            assertEquals(currentVersion, json(createResp)["product"]["version"].asLong())

            currentVersion = bumpProduct(currentVersion, "20.25", 175)

            // Request confirmation refreshes productVersion & commission from latest active product
            val reqConfResp = mockMvc.post("/api/v1/agent/applications/$appId/request-confirmation") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response
            assertEquals(200, reqConfResp.status, reqConfResp.getContentAsString(Charsets.UTF_8))
            assertEquals(currentVersion, json(reqConfResp)["product"]["version"].asLong())
            assertEquals(175, json(reqConfResp)["agentCommissionRateBps"].asInt())
            assertEquals(2_000_000L * 175 / 10_000L, json(reqConfResp)["agentCommissionAmount"].asLong())

            // 2) Bump product again to N+2 while application is in PENDING_CLIENT_CONFIRMATION
            currentVersion = bumpProduct(currentVersion, "20.50", 200)

            // Client fetches card and receives currentProduct with latest version N+2
            val clientGet = mockMvc.get("/api/v1/applications/$appId") {
                header("Authorization", "Bearer $kotovaClientToken")
            }.andReturn().response
            assertEquals(200, clientGet.status, clientGet.getContentAsString(Charsets.UTF_8))
            val clientCard = json(clientGet)
            assertFalse(clientCard["currentProduct"].isNull, "currentProduct must be present in PENDING_CLIENT_CONFIRMATION")
            assertEquals(currentVersion, clientCard["currentProduct"]["version"].asLong())

            // Client confirms using currentProduct.version -> succeeds cleanly with no deadlock
            val confirmResp = mockMvc.post("/api/v1/applications/$appId/confirm") {
                header("Authorization", "Bearer $kotovaClientToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"bureauConsent":true,"acceptedProductVersion":$currentVersion}"""
            }.andReturn().response
            assertEquals(200, confirmResp.status, confirmResp.getContentAsString(Charsets.UTF_8))
            assertEquals("SUBMITTED", json(confirmResp)["status"].asText())
            assertEquals(currentVersion, json(confirmResp)["product"]["version"].asLong())
            assertEquals(200, json(confirmResp)["agentCommissionRateBps"].asInt())
            assertEquals(2_000_000L * 200 / 10_000L, json(confirmResp)["agentCommissionAmount"].asLong())
        } finally {
            bumpProduct(
                currentVersion,
                initialProduct["baseRate"].asText(),
                initialProduct["agentCommissionRateBps"].asInt(),
            )
        }
    }

    @Test
    fun `PENDING_CLIENT_CONFIRMATION applications are excluded from bank analytics`() {
        val managerToken = token(Roles.MANAGER, fullName = "Менеджер Банка")
        val createResp = createAgentDraft(product = "INVESTMENT", purpose = "EQUIPMENT", amount = 2_000_000L)
        assertEquals(201, createResp.status)
        val appId = UUID.fromString(json(createResp)["id"].asText())

        assertEquals(
            200,
            mockMvc.post("/api/v1/agent/applications/$appId/request-confirmation") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )

        val analyticsResp = mockMvc.get("/api/v1/analytics/funnel?from=2026-01-01T00:00:00Z&to=2027-12-31T00:00:00Z") {
            header("Authorization", "Bearer $managerToken")
        }.andReturn().response
        assertEquals(200, analyticsResp.status, analyticsResp.getContentAsString(Charsets.UTF_8))
        val analytics = json(analyticsResp)
        assertFalse(
            analytics["currentStatuses"].has("PENDING_CLIENT_CONFIRMATION"),
            "PENDING_CLIENT_CONFIRMATION must not appear in bank analytics: ${analytics["currentStatuses"]}",
        )
        assertFalse(
            analytics["currentStatuses"].has("DRAFT"),
            "DRAFT must not appear in bank analytics: ${analytics["currentStatuses"]}",
        )
    }

    @Test
    fun `IdentityAgentAccessClient fails closed with 503 when identity is unconfigured or unreachable`() {
        val unconfigured = IdentityAgentAccessClient("", org.springframework.web.client.RestClient.builder())
        val ex1 = kotlin.test.assertFailsWith<ru.creditcabinet.common.exception.ApiException> {
            unconfigured.listCompanyIds(agentId)
        }
        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, ex1.httpStatus)

        val ex2 = kotlin.test.assertFailsWith<ru.creditcabinet.common.exception.ApiException> {
            unconfigured.checkAccess(agentId, kotovaCompanyId)
        }
        assertEquals(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, ex2.httpStatus)
    }

    private fun insertOffer(company: UUID): UUID {
        val id = UUID.randomUUID()
        val now = Timestamp.from(Instant.now())
        jdbc.update(
            """
            insert into pre_approved_offers
                (id, company_id, product_code, max_amount, min_term_months, max_term_months, rate, valid_until, status, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            id,
            company,
            "OVERDRAFT",
            2_000_000L,
            6,
            12,
            BigDecimal("18.00"),
            Timestamp.from(Instant.now().plus(30, ChronoUnit.DAYS)),
            "ACTIVE",
            now,
            now,
        )
        createdOfferIds += id
        return id
    }
}
