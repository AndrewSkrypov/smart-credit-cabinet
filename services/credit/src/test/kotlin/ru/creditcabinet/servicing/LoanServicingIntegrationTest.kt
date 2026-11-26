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
import ru.creditcabinet.application.domain.PipelineJobs
import ru.creditcabinet.application.domain.PipelineStep
import ru.creditcabinet.assessment.domain.Decision
import ru.creditcabinet.assessment.domain.DecisionOutcome
import ru.creditcabinet.assessment.domain.DecisionRepository
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.offer.Offer
import ru.creditcabinet.offer.OfferKind
import ru.creditcabinet.offer.OfferRepository
import ru.creditcabinet.offer.OfferStatus
import ru.creditcabinet.offer.SigningSessionRecord
import ru.creditcabinet.offer.SigningSessions
import ru.creditcabinet.pipeline.FakeSources
import ru.creditcabinet.pipeline.MutableClock
import ru.creditcabinet.pipeline.PipelineFakesConfig
import ru.creditcabinet.pipeline.PipelineWorker
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.LoanMath
import ru.creditcabinet.servicing.application.LoanServicingService
import java.math.BigDecimal
import java.time.ZoneOffset
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
class LoanServicingIntegrationTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var worker: PipelineWorker

    @Autowired private lateinit var clock: MutableClock

    @Autowired private lateinit var sources: FakeSources

    @Autowired private lateinit var applications: ApplicationRepository

    @Autowired private lateinit var decisions: DecisionRepository

    @Autowired private lateinit var offers: OfferRepository

    @Autowired private lateinit var signing: SigningSessions

    @Autowired private lateinit var jobs: PipelineJobs

    @Autowired private lateinit var servicingService: LoanServicingService

    @MockitoBean private lateinit var companies: CompanyDirectory

    @MockitoBean private lateinit var agentAccess: AgentAccessPort

    private val companyId = UUID.randomUUID()
    private val otherCompanyId = UUID.randomUUID()
    private val agentId = UUID.randomUUID()
    private val companySnapshot = CompanySnapshot(companyId, "7701234567", "ООО Вектор", LegalForm.LEGAL_ENTITY)
    private val otherSnapshot = CompanySnapshot(otherCompanyId, "7707654321", "ООО Другая", LegalForm.LEGAL_ENTITY)

    private val clientToken by lazy { token(Roles.CLIENT, company = companyId) }
    private val otherClientToken by lazy { token(Roles.CLIENT, company = otherCompanyId) }
    private val agentToken by lazy { token(Roles.AGENT, userId = agentId) }
    private val managerToken by lazy { token(Roles.MANAGER) }

    private val createdApplicationIds = mutableListOf<UUID>()
    private var agentMandateActive = true

    @BeforeTest
    fun setUp() {
        agentMandateActive = true
        jdbc.update("update pipeline_jobs set status = 'CANCELLED' where status in ('PENDING', 'RUNNING')")
        sources.companies[companySnapshot.inn] = FakeSources.healthy(companySnapshot.inn, clock.instant().atOffset(ZoneOffset.UTC).toLocalDate())
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
            if (reqAgent == agentId && reqCompany == companyId && agentMandateActive) {
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
            jdbc.update("delete from signing_sessions where application_id = ?", appId)
            jdbc.update("delete from offers where application_id = ?", appId)
            jdbc.update("delete from decisions where application_id = ?", appId)
            jdbc.update("delete from pipeline_jobs where application_id = ?", appId)
        }
        createdApplicationIds.clear()
    }

    private fun token(role: String, company: UUID? = null, userId: UUID = UUID.randomUUID()): String =
        Jwts.builder()
            .subject(userId.toString())
            .claim(TokenClaims.ROLE, role)
            .claim(TokenClaims.FULL_NAME, "Тест")
            .apply { company?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
            .claim(TokenClaims.CAN_SIGN, company != null)
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(verifier.signingKey)
            .compact()

    private fun json(response: MockHttpServletResponse): JsonNode =
        mapper.readTree(response.getContentAsString(Charsets.UTF_8))

    private fun disburseApplication(
        productCode: String,
        purpose: FinancingPurpose,
        amount: Long,
        termMonths: Int,
        rate: BigDecimal,
        revolving: Boolean,
        withAgent: Boolean = false,
    ): UUID {
        val now = clock.instant()
        val app = applications.save(
            LoanApplication(
                company = companySnapshot,
                createdBy = if (withAgent) agentId else UUID.randomUUID(),
                terms = ApplicationTerms(
                    productCode = productCode,
                    purpose = purpose,
                    amount = amount,
                    termMonths = termMonths,
                    declaredRevenue12m = 30_000_000L,
                    bureauConsent = true,
                ),
                status = ApplicationStatus.DISBURSING,
                statusChangedAt = now,
                createdAt = now,
                updatedAt = now,
                submittedAt = now,
                productCatalogSnapshot = mapOf(productCode to 1L),
                agentId = if (withAgent) agentId else null,
                agentName = if (withAgent) "Агент Тест" else null,
            ),
        )
        createdApplicationIds += app.id

        val decision = Decision(
            applicationId = app.id,
            ruleSetVersion = 1,
            decidedByType = ru.creditcabinet.application.domain.ActorType.SYSTEM,
            decidedBy = null,
            score = 85,
            rating = ru.creditcabinet.assessment.domain.Rating.A,
            stopFactors = emptyList(),
            factors = emptyList(),
            outcome = DecisionOutcome.APPROVE,
            reasonCode = null,
            manualReasons = emptyList(),
            recommendation = null,
            inputs = emptyMap(),
            comment = null,
            createdAt = now,
        )
        decisions.save(decision)

        val calc = LoanMath.calculate(amount, rate, termMonths, revolving)
        val offer = Offer(
            applicationId = app.id,
            decisionId = decision.id,
            kind = OfferKind.APPROVED,
            productCode = productCode,
            amount = amount,
            termMonths = termMonths,
            rate = rate,
            monthlyPayment = calc.monthlyPayment,
            totalPayment = calc.totalPayment,
            validUntil = now.plusSeconds(86400),
            status = OfferStatus.ACCEPTED,
            createdAt = now,
            decidedAt = now,
            productVersion = 1,
        )
        offers.save(offer)

        val session = SigningSessionRecord(
            applicationId = app.id,
            offerId = offer.id,
            externalSessionId = UUID.randomUUID(),
            code = "123456",
            expiresAt = now.plusSeconds(600),
            createdAt = now,
        )
        signing.save(session)
        signing.markSigned(session.id, UUID.randomUUID(), now, UUID.randomUUID())

        jobs.enqueue(app.id, PipelineStep.DISBURSEMENT, now)
        assertEquals(1, worker.runDue())
        return app.id
    }

    @Test
    fun `disbursement automatically creates loan account with 50 percent tranche for revolving and 100 percent for standard`() {
        val revolvingAppId = disburseApplication(
            productCode = "OVERDRAFT",
            purpose = FinancingPurpose.CASH_GAP,
            amount = 2_000_000L,
            termMonths = 12,
            rate = BigDecimal("19.00"),
            revolving = true,
            withAgent = true,
        )

        val revResp = mockMvc.get("/api/v1/applications/$revolvingAppId/loan") {
            header("Authorization", "Bearer $clientToken")
        }.andReturn().response
        assertEquals(200, revResp.status, revResp.getContentAsString(Charsets.UTF_8))
        val revJson = json(revResp)
        assertEquals("ACTIVE", revJson["status"].asText())
        assertEquals(2_000_000L, revJson["approvedLimit"].asLong())
        assertEquals(1_000_000L, revJson["disbursedAmount"].asLong())
        assertEquals(0, revJson["outstandingPrincipal"].decimalValue().compareTo(BigDecimal("1000000.00")))
        assertTrue(revJson["revolving"].asBoolean())
        assertEquals(1, revJson["tranches"].size())
        assertEquals(1_000_000L, revJson["tranches"][0]["amount"].asLong())
        assertEquals(12, revJson["payments"].size())

        // Read-only access for BANK_STAFF and AGENT with active mandate
        assertEquals(
            200,
            mockMvc.get("/api/v1/applications/$revolvingAppId/loan") {
                header("Authorization", "Bearer $managerToken")
            }.andReturn().response.status,
        )
        assertEquals(
            403,
            mockMvc.get("/api/v1/applications/$revolvingAppId/loan") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )
        assertEquals(
            200,
            mockMvc.get("/api/v1/agent/applications/$revolvingAppId/loan") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )

        // Revoked mandate -> 404 for AGENT
        agentMandateActive = false
        assertEquals(
            404,
            mockMvc.get("/api/v1/agent/applications/$revolvingAppId/loan") {
                header("Authorization", "Bearer $agentToken")
            }.andReturn().response.status,
        )

        // Other company's CLIENT -> 404
        assertEquals(
            404,
            mockMvc.get("/api/v1/applications/$revolvingAppId/loan") {
                header("Authorization", "Bearer $otherClientToken")
            }.andReturn().response.status,
        )

        // Non-revolving product gets 100% initial tranche
        val stdAppId = disburseApplication(
            productCode = "WORKING_CAPITAL",
            purpose = FinancingPurpose.WORKING_CAPITAL,
            amount = 3_000_000L,
            termMonths = 12,
            rate = BigDecimal("20.00"),
            revolving = false,
        )
        val stdResp = mockMvc.get("/api/v1/applications/$stdAppId/loan") {
            header("Authorization", "Bearer $clientToken")
        }.andReturn().response
        assertEquals(200, stdResp.status, stdResp.getContentAsString(Charsets.UTF_8))
        val stdJson = json(stdResp)
        assertEquals(3_000_000L, stdJson["approvedLimit"].asLong())
        assertEquals(3_000_000L, stdJson["disbursedAmount"].asLong())
        assertEquals(0, stdJson["outstandingPrincipal"].decimalValue().compareTo(BigDecimal("3000000.00")))
        assertFalse(stdJson["revolving"].asBoolean())
        assertEquals(1, stdJson["tranches"].size())
        assertEquals(3_000_000L, stdJson["tranches"][0]["amount"].asLong())

        // Idempotent openOnDisbursement returns the same account without duplicating tranches/payments
        val app = applications.find(stdAppId)!!
        val existingId = UUID.fromString(stdJson["id"].asText())
        val repeated = servicingService.openOnDisbursement(
            app,
            ru.creditcabinet.offer.Loan(
                applicationId = stdAppId,
                contractNumber = stdJson["contractNumber"].asText(),
                loanAccount = "45206810000000000001",
                settlementAccount = "40702810000000000001",
                amount = 3_000_000L,
                termMonths = 12,
                rate = BigDecimal("20.00"),
                revolving = false,
                disbursedAt = clock.instant(),
                maturityDate = java.time.LocalDate.of(2027, 10, 1),
            ),
        )
        assertEquals(existingId, repeated.id)
    }

    @Test
    fun `revolving tranche drawdown updates balance and schedule while invalid drawdowns and non-client callers fail safely`() {
        val revAppId = disburseApplication(
            productCode = "OVERDRAFT",
            purpose = FinancingPurpose.CASH_GAP,
            amount = 2_000_000L,
            termMonths = 12,
            rate = BigDecimal("19.00"),
            revolving = true,
            withAgent = true,
        )
        val initialLoan = json(
            mockMvc.get("/api/v1/applications/$revAppId/loan") {
                header("Authorization", "Bearer $clientToken")
            }.andReturn().response,
        )
        val loanId = initialLoan["id"].asText()

        // Review Focus #4: AGENT and BANK_STAFF get 403 on mutations
        for (nonClientToken in listOf(agentToken, managerToken)) {
            assertEquals(
                403,
                mockMvc.post("/api/v1/loans/$loanId/tranches") {
                    header("Authorization", "Bearer $nonClientToken")
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"amount":200000,"purpose":"Тест"}"""
                }.andReturn().response.status,
            )
            assertEquals(
                403,
                mockMvc.post("/api/v1/loans/$loanId/payments/next") {
                    header("Authorization", "Bearer $nonClientToken")
                }.andReturn().response.status,
            )
            assertEquals(
                403,
                mockMvc.post("/api/v1/loans/$loanId/repay-early") {
                    header("Authorization", "Bearer $nonClientToken")
                }.andReturn().response.status,
            )
        }

        // Other company CLIENT gets 404
        assertEquals(
            404,
            mockMvc.post("/api/v1/loans/$loanId/tranches") {
                header("Authorization", "Bearer $otherClientToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"amount":200000,"purpose":"Чужой"}"""
            }.andReturn().response.status,
        )

        // Review Focus #4: amount <= 0 -> 400, amount > remaining -> 409 without mutating balance
        assertEquals(
            400,
            mockMvc.post("/api/v1/loans/$loanId/tranches") {
                header("Authorization", "Bearer $clientToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"amount":0,"purpose":"Ноль"}"""
            }.andReturn().response.status,
        )
        assertEquals(
            409,
            mockMvc.post("/api/v1/loans/$loanId/tranches") {
                header("Authorization", "Bearer $clientToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"amount":1500000,"purpose":"Сверх лимита"}"""
            }.andReturn().response.status,
        )

        // Balance unchanged after rejected requests
        val afterRejected = json(
            mockMvc.get("/api/v1/applications/$revAppId/loan") {
                header("Authorization", "Bearer $clientToken")
            }.andReturn().response,
        )
        assertEquals(1_000_000L, afterRejected["disbursedAmount"].asLong())

        // Valid second tranche
        val firstPaymentBefore = initialLoan["payments"][0]["paymentAmount"].decimalValue()
        val trancheResp = mockMvc.post("/api/v1/loans/$loanId/tranches") {
            header("Authorization", "Bearer $clientToken")
            contentType = MediaType.APPLICATION_JSON
            content = """{"amount":500000,"purpose":"Закупка сырья"}"""
        }.andReturn().response
        assertEquals(200, trancheResp.status, trancheResp.getContentAsString(Charsets.UTF_8))
        val afterTranche = json(trancheResp)
        assertEquals(1_500_000L, afterTranche["disbursedAmount"].asLong())
        assertEquals(0, afterTranche["outstandingPrincipal"].decimalValue().compareTo(BigDecimal("1500000.00")))
        assertEquals(2, afterTranche["tranches"].size())
        assertEquals(500_000L, afterTranche["tranches"][1]["amount"].asLong())
        assertTrue(afterTranche["payments"][0]["paymentAmount"].decimalValue() > firstPaymentBefore)

        // Non-revolving loan rejects tranche -> 409
        val stdAppId = disburseApplication(
            productCode = "WORKING_CAPITAL",
            purpose = FinancingPurpose.WORKING_CAPITAL,
            amount = 1_500_000L,
            termMonths = 12,
            rate = BigDecimal("20.00"),
            revolving = false,
        )
        val stdLoanId = json(
            mockMvc.get("/api/v1/applications/$stdAppId/loan") {
                header("Authorization", "Bearer $clientToken")
            }.andReturn().response,
        )["id"].asText()
        assertEquals(
            409,
            mockMvc.post("/api/v1/loans/$stdLoanId/tranches") {
                header("Authorization", "Bearer $clientToken")
                contentType = MediaType.APPLICATION_JSON
                content = """{"amount":100000,"purpose":"Нельзя"}"""
            }.andReturn().response.status,
        )
    }

    @Test
    fun `payments next reduces balance and repay early closes loan`() {
        val appId = disburseApplication(
            productCode = "WORKING_CAPITAL",
            purpose = FinancingPurpose.WORKING_CAPITAL,
            amount = 1_200_000L,
            termMonths = 6,
            rate = BigDecimal("18.00"),
            revolving = false,
        )
        val opened = json(
            mockMvc.get("/api/v1/applications/$appId/loan") {
                header("Authorization", "Bearer $clientToken")
            }.andReturn().response,
        )
        val loanId = opened["id"].asText()
        val initialPrincipal = opened["outstandingPrincipal"].decimalValue()

        // Pay next scheduled payment
        val payResp = mockMvc.post("/api/v1/loans/$loanId/payments/next") {
            header("Authorization", "Bearer $clientToken")
        }.andReturn().response
        assertEquals(200, payResp.status, payResp.getContentAsString(Charsets.UTF_8))
        val afterPay = json(payResp)
        assertEquals("ACTIVE", afterPay["status"].asText())
        assertEquals("PAID", afterPay["payments"][0]["status"].asText())
        assertEquals("SCHEDULED", afterPay["payments"][1]["status"].asText())
        assertTrue(afterPay["outstandingPrincipal"].decimalValue() < initialPrincipal)

        // Early repayment closes the loan
        val repayResp = mockMvc.post("/api/v1/loans/$loanId/repay-early") {
            header("Authorization", "Bearer $clientToken")
        }.andReturn().response
        assertEquals(200, repayResp.status, repayResp.getContentAsString(Charsets.UTF_8))
        val afterRepay = json(repayResp)
        assertEquals("REPAID", afterRepay["status"].asText())
        assertEquals(0, afterRepay["outstandingPrincipal"].decimalValue().compareTo(BigDecimal.ZERO))

        // Further mutations on REPAID loan return 409
        assertEquals(
            409,
            mockMvc.post("/api/v1/loans/$loanId/payments/next") {
                header("Authorization", "Bearer $clientToken")
            }.andReturn().response.status,
        )
        assertEquals(
            409,
            mockMvc.post("/api/v1/loans/$loanId/repay-early") {
                header("Authorization", "Bearer $clientToken")
            }.andReturn().response.status,
        )
    }
}
