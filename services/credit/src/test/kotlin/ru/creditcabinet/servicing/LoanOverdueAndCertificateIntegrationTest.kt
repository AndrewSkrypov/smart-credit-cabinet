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
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
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
@Import(PipelineFakesConfig::class)
class LoanOverdueAndCertificateIntegrationTest {
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
    private val companySnapshot = CompanySnapshot(companyId, "7701234569", "ООО Просрочка Тест", LegalForm.LEGAL_ENTITY)
    private val otherSnapshot = CompanySnapshot(otherCompanyId, "7707654323", "ООО Чужая Компания", LegalForm.LEGAL_ENTITY)

    private val clientToken by lazy { token(Roles.CLIENT, company = companyId) }
    private val otherClientToken by lazy { token(Roles.CLIENT, company = otherCompanyId) }
    private val agentToken by lazy { token(Roles.AGENT, userId = agentId) }

    private val createdApplicationIds = mutableListOf<UUID>()
    private lateinit var initialClockInstant: Instant

    @BeforeTest
    fun setUp() {
        initialClockInstant = clock.instant()
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
        clock.advance(Duration.between(clock.instant(), initialClockInstant))
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
    fun `overdue payment blocks tranche until paid and maturity date blocks tranche permanently`() {
        val (appId, loanId) = openRevolvingLoan(approvedLimit = 2_000_000L, termMonths = 6)

        // Move clock 40 days forward -> first scheduled payment (+1 month) is now overdue
        clock.advance(Duration.ofDays(40))

        val overdueView = json(
            mockMvc.get("/api/v1/applications/$appId/loan") {
                header("Authorization", "Bearer $clientToken")
            }.andReturn().response,
        )
        assertTrue(overdueView["hasOverduePayments"].asBoolean())
        assertTrue(overdueView["overdueDays"].asLong() > 0L)
        assertTrue(overdueView["overdueAmount"].decimalValue() > BigDecimal.ZERO)
        assertTrue(overdueView["payments"][0]["overdue"].asBoolean())

        // Tranche is rejected with 409 while overdue
        val blockedResp = mockMvc.post("/api/v1/loans/$loanId/tranches") {
            header("Authorization", "Bearer $clientToken")
            header("Idempotency-Key", UUID.randomUUID())
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount":200000,"purpose":"При просрочке"}"""
        }.andReturn().response
        assertEquals(409, blockedResp.status, blockedResp.getContentAsString(Charsets.UTF_8))

        // Paying the overdue scheduled payment clears overdue status
        val paidResp = mockMvc.post("/api/v1/loans/$loanId/payments/next") {
            header("Authorization", "Bearer $clientToken")
            header("Idempotency-Key", UUID.randomUUID())
            contentType = MediaType.APPLICATION_JSON
            content = """{"paymentId":"${nextPaymentId(loanId)}"}"""
        }.andReturn().response
        assertEquals(200, paidResp.status, paidResp.getContentAsString(Charsets.UTF_8))
        assertFalse(json(paidResp)["hasOverduePayments"].asBoolean())

        // Tranche succeeds once overdue is cleared
        val okTranche = mockMvc.post("/api/v1/loans/$loanId/tranches") {
            header("Authorization", "Bearer $clientToken")
            header("Idempotency-Key", UUID.randomUUID())
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount":200000,"purpose":"После оплаты просрочки"}"""
        }.andReturn().response
        assertEquals(200, okTranche.status, okTranche.getContentAsString(Charsets.UTF_8))

        // Moving clock past maturityDate (+6 months) blocks tranche even with available limit
        clock.advance(Duration.ofDays(200))
        val expiredResp = mockMvc.post("/api/v1/loans/$loanId/tranches") {
            header("Authorization", "Bearer $clientToken")
            header("Idempotency-Key", UUID.randomUUID())
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount":100000,"purpose":"После срока линии"}"""
        }.andReturn().response
        assertEquals(409, expiredResp.status, expiredResp.getContentAsString(Charsets.UTF_8))
    }

    @Test
    fun `closure certificate returns 409 while active, 200 after repay-early, 404 for foreign company and 403 for agent`() {
        val (_, loanId) = openRevolvingLoan(approvedLimit = 1_000_000L, termMonths = 6)

        // Active loan -> 409
        assertEquals(
            409,
            mockMvc.get("/api/v1/loans/$loanId/closure-certificate") {
                header("Authorization", "Bearer $clientToken")
            }.andReturn().response.status,
        )

        // Repay early -> REPAID
        assertEquals(
            200,
            mockMvc.post("/api/v1/loans/$loanId/repay-early") {
                header("Authorization", "Bearer $clientToken")
            }.andReturn().response.status,
        )

        // Client receives closure certificate
        val certResp = mockMvc.get("/api/v1/loans/$loanId/closure-certificate") {
            header("Authorization", "Bearer $clientToken")
        }.andReturn().response
        assertEquals(200, certResp.status, certResp.getContentAsString(Charsets.UTF_8))
        val cert = json(certResp)
        assertTrue(cert["certificateNumber"].asText().startsWith("CERT-KD-"))
        assertEquals(0, cert["outstandingPrincipal"].decimalValue().compareTo(BigDecimal.ZERO))
        assertTrue(cert["statement"].asText().contains("исполнены в полном объёме"))

        // Foreign company -> 404, Agent -> 403
        assertEquals(
            404,
            mockMvc.get("/api/v1/loans/$loanId/closure-certificate") {
                header("Authorization", "Bearer $otherClientToken")
            }.andReturn().response.status,
        )
        assertEquals(
            403,
            mockMvc.get("/api/v1/loans/$loanId/closure-certificate") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )
    }

    private fun openRevolvingLoan(approvedLimit: Long, termMonths: Int): Pair<UUID, String> {
        val now = clock.instant()
        val app = applications.save(
            LoanApplication(
                company = companySnapshot,
                createdBy = agentId,
                terms = ApplicationTerms(
                    productCode = "OVERDRAFT",
                    purpose = FinancingPurpose.WORKING_CAPITAL,
                    amount = approvedLimit,
                    termMonths = termMonths,
                    declaredRevenue12m = 20_000_000L,
                    declaredMonthlyDebtPayments = 0L,
                    bureauConsent = true,
                ),
                status = ApplicationStatus.DISBURSED,
                clientHandoffAt = now,
                statusChangedAt = now,
                createdAt = now,
                updatedAt = now,
                agentId = agentId,
                agentName = "Агент Просрочка",
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
            termMonths = termMonths,
            revolving = true,
            disbursedAt = now,
            maturityDate = baseDate.plusMonths(termMonths.toLong()),
            firstPaymentBaseDate = baseDate,
        )
        loanAccountStore.create(account)
        return app.id to account.id.toString()
    }

    private fun nextPaymentId(loanId: String): String =
        jdbc.queryForObject(
            "select id::text from loan_payments where loan_account_id = ?::uuid and status = 'SCHEDULED' order by payment_number limit 1",
            String::class.java,
            loanId,
        ) ?: UUID.randomUUID().toString()

    private fun json(response: MockHttpServletResponse): JsonNode =
        mapper.readTree(response.getContentAsString(Charsets.UTF_8))

    private fun token(role: String, userId: UUID = UUID.randomUUID(), company: UUID? = null): String =
        Jwts.builder()
            .subject(userId.toString())
            .claim(TokenClaims.ROLE, role)
            .claim(TokenClaims.FULL_NAME, "Тест Просрочка")
            .apply { company?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
            .claim(TokenClaims.CAN_SIGN, company != null)
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(verifier.signingKey)
            .compact()
}
