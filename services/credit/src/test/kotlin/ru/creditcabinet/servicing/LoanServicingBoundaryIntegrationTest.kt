package ru.creditcabinet.servicing

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import ru.creditcabinet.agent.domain.AgentAccessDecision
import ru.creditcabinet.agent.domain.AgentAccessPort
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.CompanyDirectory
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.pipeline.MutableClock
import ru.creditcabinet.pipeline.PipelineFakesConfig
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.servicing.domain.LoanAccount
import ru.creditcabinet.servicing.domain.LoanAccountStore
import java.math.BigDecimal
import java.time.ZoneId
import java.util.Date
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(PipelineFakesConfig::class)
class LoanServicingBoundaryIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var clock: MutableClock

    @Autowired private lateinit var applications: ApplicationRepository

    @Autowired private lateinit var loanAccountStore: LoanAccountStore

    @MockitoBean private lateinit var companies: CompanyDirectory

    @MockitoBean private lateinit var agentAccess: AgentAccessPort

    private val companyId = UUID.randomUUID()
    private val otherCompanyId = UUID.randomUUID()
    private val agentId = UUID.randomUUID()
    private val companySnapshot = CompanySnapshot(companyId, "7701234568", "ООО Граница Кредит", LegalForm.LEGAL_ENTITY)
    private val otherSnapshot = CompanySnapshot(otherCompanyId, "7707654322", "ООО Чужой Клиент", LegalForm.LEGAL_ENTITY)

    private val clientToken by lazy { token(Roles.CLIENT, company = companyId) }
    private val otherClientToken by lazy { token(Roles.CLIENT, company = otherCompanyId) }
    private val agentToken by lazy { token(Roles.AGENT, userId = agentId) }

    private val createdApplicationIds = mutableListOf<UUID>()

    @BeforeTest
    fun setUp() {
        whenever(companies.find(any())).thenAnswer { inv ->
            when (inv.getArgument<UUID>(0)) {
                companyId -> companySnapshot
                otherCompanyId -> otherSnapshot
                else -> null
            }
        }
        whenever(agentAccess.checkAccess(any(), any())).thenAnswer { inv ->
            val reqAgent = inv.getArgument<UUID>(0)
            val reqCompany = inv.getArgument<UUID>(1)
            if (reqAgent == agentId && reqCompany == companyId) {
                AgentAccessDecision(allowed = true, company = companySnapshot)
            } else {
                AgentAccessDecision(allowed = false, company = null)
            }
        }
    }

    @AfterTest
    fun tearDown() {
        for (appId in createdApplicationIds) {
            jdbc.update("delete from loan_accounts where application_id = ?", appId)
            jdbc.update("delete from loans where application_id = ?", appId)
            jdbc.update("alter table application_events disable trigger application_events_no_update")
            jdbc.update("delete from application_events where application_id = ?", appId)
            jdbc.update("alter table application_events enable trigger application_events_no_update")
            jdbc.update("delete from applications where id = ?", appId)
        }
        createdApplicationIds.clear()
    }

    @Test
    fun `revolving tranche rejects zero, negative and over-limit amounts`() {
        val (appId, loanId) = openRevolvingLoan(approvedLimit = 2_000_000L)

        // Initial tranche for revolving is 50% (1_000_000), remaining limit is 1_000_000
        for (invalidAmount in listOf(0L, -50_000L)) {
            val resp = mockMvc.post("/api/v1/loans/$loanId/tranches") {
                header("Authorization", "Bearer $clientToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"amount":$invalidAmount,"purpose":"Невалидный транш"}"""
            }.andReturn().response
            assertEquals(400, resp.status, "Expected 400 for amount=$invalidAmount: ${resp.getContentAsString(Charsets.UTF_8)}")
        }

        val overLimitResp = mockMvc.post("/api/v1/loans/$loanId/tranches") {
            header("Authorization", "Bearer $clientToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount":1000001,"purpose":"Превышение лимита"}"""
        }.andReturn().response
        assertTrue(
            overLimitResp.status in listOf(400, 409),
            "Expected 400/409 for over-limit tranche, got ${overLimitResp.status}",
        )

        // Drawing exact remaining limit succeeds, next 1 RUB fails
        val exactResp = mockMvc.post("/api/v1/loans/$loanId/tranches") {
            header("Authorization", "Bearer $clientToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount":1000000,"purpose":"Выборка остатка"}"""
        }.andReturn().response
        assertEquals(200, exactResp.status, exactResp.getContentAsString(Charsets.UTF_8))

        val exhaustedResp = mockMvc.post("/api/v1/loans/$loanId/tranches") {
            header("Authorization", "Bearer $clientToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount":1,"purpose":"Лимит исчерпан"}"""
        }.andReturn().response
        assertTrue(
            exhaustedResp.status in listOf(400, 409),
            "Expected 400/409 when limit is exhausted, got ${exhaustedResp.status}",
        )

        // Agent can read via agent endpoint (200)
        assertEquals(
            200,
            mockMvc.get("/api/v1/agent/applications/$appId/loan") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )
    }

    @Test
    fun `foreign company gets 404 and agent gets 403 on all loan mutations`() {
        val (appId, loanId) = openRevolvingLoan(approvedLimit = 2_000_000L)

        // Foreign company IDOR checks -> 404
        assertEquals(
            404,
            mockMvc.get("/api/v1/applications/$appId/loan") {
                header("Authorization", "Bearer $otherClientToken")
            }.andReturn().response.status,
        )
        assertEquals(
            404,
            mockMvc.post("/api/v1/loans/$loanId/tranches") {
                header("Authorization", "Bearer $otherClientToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"amount":100000,"purpose":"Чужой транш"}"""
            }.andReturn().response.status,
        )
        assertEquals(
            404,
            mockMvc.post("/api/v1/loans/$loanId/payments/next") {
                header("Authorization", "Bearer $otherClientToken")
            }.andReturn().response.status,
        )
        assertEquals(
            404,
            mockMvc.post("/api/v1/loans/$loanId/repay-early") {
                header("Authorization", "Bearer $otherClientToken")
            }.andReturn().response.status,
        )

        // Agent cannot mutate loan -> 403
        assertEquals(
            403,
            mockMvc.post("/api/v1/loans/$loanId/tranches") {
                header("Authorization", "Bearer $agentToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"amount":100000,"purpose":"Агентский транш"}"""
            }.andReturn().response.status,
        )
        assertEquals(
            403,
            mockMvc.post("/api/v1/loans/$loanId/payments/next") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )
        assertEquals(
            403,
            mockMvc.post("/api/v1/loans/$loanId/repay-early") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )
    }

    private fun openRevolvingLoan(approvedLimit: Long): Pair<UUID, String> {
        val now = clock.instant()
        val app = applications.save(
            LoanApplication(
                company = companySnapshot,
                createdBy = agentId,
                terms = ApplicationTerms(
                    productCode = "OVERDRAFT",
                    purpose = FinancingPurpose.WORKING_CAPITAL,
                    amount = approvedLimit,
                    termMonths = 12,
                    declaredRevenue12m = 20_000_000L,
                    declaredMonthlyDebtPayments = 0L,
                    bureauConsent = true,
                ),
                status = ApplicationStatus.DISBURSED,
                statusChangedAt = now,
                createdAt = now,
                updatedAt = now,
                agentId = agentId,
                agentName = "Агент Один",
            ),
        )
        createdApplicationIds += app.id
        val baseDate = now.atZone(ZoneId.of("Europe/Moscow")).toLocalDate()
        val account = LoanAccount.open(
            applicationId = app.id,
            companyId = companyId,
            contractNumber = "KD-${app.id.toString().take(8)}",
            approvedLimit = approvedLimit,
            rate = BigDecimal("18.00"),
            termMonths = 12,
            revolving = true,
            disbursedAt = now,
            maturityDate = baseDate.plusMonths(12),
            firstPaymentBaseDate = baseDate,
        )
        loanAccountStore.create(account)
        return app.id to account.id.toString()
    }

    private fun json(response: MockHttpServletResponse): JsonNode =
        mapper.readTree(response.getContentAsString(Charsets.UTF_8))

    private fun token(role: String, userId: UUID = UUID.randomUUID(), company: UUID? = null): String =
        Jwts.builder()
            .subject(userId.toString())
            .claim(TokenClaims.ROLE, role)
            .claim(TokenClaims.FULL_NAME, "Тест Обслуживания")
            .apply { company?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
            .claim(TokenClaims.CAN_SIGN, company != null)
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(verifier.signingKey)
            .compact()
}
