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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.CommissionStatus
import ru.creditcabinet.application.domain.CompanyDirectory
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.assessment.domain.Assessment
import ru.creditcabinet.assessment.domain.CreditPolicy
import ru.creditcabinet.assessment.domain.IndustryRisk
import ru.creditcabinet.assessment.domain.PolicyDecision
import ru.creditcabinet.assessment.domain.Rating
import ru.creditcabinet.assessment.domain.RejectReason
import ru.creditcabinet.assessment.domain.RevenueSource
import ru.creditcabinet.assessment.domain.RuleSet
import ru.creditcabinet.assessment.domain.StopFactor
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.preapproved.application.PreApprovedOfferService
import ru.creditcabinet.preapproved.domain.PreApprovedOffer
import ru.creditcabinet.preapproved.domain.PreApprovedOfferStatus
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductCatalog
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
class PreApprovedOfferTest {
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
    private lateinit var preApprovedService: PreApprovedOfferService

    @Autowired
    private lateinit var products: ProductCatalog

    @MockitoBean
    private lateinit var companies: CompanyDirectory

    private val companyId = UUID.randomUUID()
    private val otherCompanyId = UUID.randomUUID()
    private val client by lazy { token("CLIENT", companyId) }
    private val otherClient by lazy { token("CLIENT", otherCompanyId) }
    private val createdOfferIds = mutableListOf<UUID>()

    @BeforeTest
    fun setUp() {
        whenever(companies.find(any())).thenAnswer {
            CompanySnapshot(it.getArgument(0), "000100000004", "ИП Тест", LegalForm.ENTREPRENEUR)
        }
    }

    @AfterTest
    fun tearDown() {
        for (offerId in createdOfferIds) {
            jdbc.update("update applications set pre_approved_offer_id = null where pre_approved_offer_id = ?", offerId)
            jdbc.update("delete from pre_approved_offers where id = ?", offerId)
        }
        createdOfferIds.clear()
    }

    private fun token(role: String, company: UUID? = null, userId: UUID = UUID.randomUUID()): String =
        Jwts.builder()
            .subject(userId.toString())
            .claim(TokenClaims.ROLE, role)
            .claim(TokenClaims.FULL_NAME, "Тест Пользователь")
            .apply { company?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
            .claim(TokenClaims.CAN_SIGN, company != null)
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(verifier.signingKey)
            .compact()

    private fun json(response: org.springframework.mock.web.MockHttpServletResponse): JsonNode =
        mapper.readTree(response.getContentAsString(Charsets.UTF_8))

    private fun insertOffer(
        company: UUID = companyId,
        productCode: String = "WORKING_CAPITAL",
        maxAmount: Long = 3_000_000L,
        minTermMonths: Int = 6,
        maxTermMonths: Int = 24,
        rate: BigDecimal = BigDecimal("18.50"),
        validUntil: Instant = Instant.now().plus(30, ChronoUnit.DAYS),
        status: String = "ACTIVE",
    ): UUID {
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
            productCode,
            maxAmount,
            minTermMonths,
            maxTermMonths,
            rate,
            Timestamp.from(validUntil),
            status,
            now,
            now,
        )
        createdOfferIds += id
        return id
    }

    @Test
    fun `client sees only active non-expired pre-approved offers for their own company`() {
        val activeId = insertOffer(company = companyId, status = "ACTIVE")
        insertOffer(company = companyId, status = "USED")
        insertOffer(company = companyId, status = "ACTIVE", validUntil = Instant.now().minus(1, ChronoUnit.DAYS))
        insertOffer(company = otherCompanyId, status = "ACTIVE")

        val response = mockMvc.get("/api/v1/pre-approved-offers") {
            header("Authorization", "Bearer $client")
        }.andReturn().response

        assertEquals(200, response.status, response.getContentAsString(Charsets.UTF_8))
        val items = json(response)
        assertEquals(1, items.size())
        assertEquals(activeId.toString(), items[0]["id"].asText())
        assertEquals("WORKING_CAPITAL", items[0]["productCode"].asText())
        assertEquals(3_000_000L, items[0]["maxAmount"].asLong())
        assertEquals(0, BigDecimal("18.50").compareTo(items[0]["rate"].decimalValue()))
    }

    @Test
    fun `demo companies have seeded active pre-approved offers`() {
        val kotova = UUID.fromString("6f1d4c1e-0b8e-4c55-9a51-000000000001")
        val mebel = UUID.fromString("6f1d4c1e-0b8e-4c55-9a51-000000000002")
        val vector = UUID.fromString("6f1d4c1e-0b8e-4c55-9a51-000000000003")

        val offers = preApprovedService.listForCompanies(listOf(kotova, mebel, vector))
        assertTrue(offers.any { it.companyId == kotova && it.status == PreApprovedOfferStatus.ACTIVE })
        assertTrue(offers.any { it.companyId == mebel && it.status == PreApprovedOfferStatus.ACTIVE })
        assertTrue(offers.any { it.companyId == vector && it.status == PreApprovedOfferStatus.ACTIVE })
    }

    @Test
    fun `apply creates prefilled draft, marks offer USED, and blocks reuse or foreign access`() {
        val offerId = insertOffer(maxAmount = 2_500_000L, minTermMonths = 6, maxTermMonths = 18, rate = BigDecimal("17.90"))

        val foreign = mockMvc.post("/api/v1/pre-approved-offers/$offerId/apply") {
            header("Authorization", "Bearer $otherClient")
        }.andReturn().response
        assertEquals(404, foreign.status)

        val response = mockMvc.post("/api/v1/pre-approved-offers/$offerId/apply") {
            header("Authorization", "Bearer $client")
        }.andReturn().response
        assertEquals(201, response.status, response.getContentAsString(Charsets.UTF_8))

        val body = json(response)
        val appId = UUID.fromString(body["id"].asText())
        assertEquals("DRAFT", body["status"].asText())
        assertEquals("WORKING_CAPITAL", body["productCode"].asText())
        assertEquals(2_500_000L, body["amount"].asLong())
        assertEquals(18, body["termMonths"].asInt())
        assertEquals(offerId.toString(), body["preApprovedOfferId"].asText())

        val storedApp = assertNotNull(applications.find(appId))
        assertEquals(offerId, storedApp.preApprovedOfferId)
        assertEquals(2_500_000L, storedApp.terms.amount)
        assertEquals(18, storedApp.terms.termMonths)

        val offerStatus = jdbc.queryForObject("select status from pre_approved_offers where id = ?", String::class.java, offerId)
        assertEquals("USED", offerStatus)

        val secondApply = mockMvc.post("/api/v1/pre-approved-offers/$offerId/apply") {
            header("Authorization", "Bearer $client")
        }.andReturn().response
        assertEquals(409, secondApply.status)
    }

    @Test
    fun `apply caps draft terms at current product limits and leaves incompatible offers unused`() {
        val product = products.find("WORKING_CAPITAL")!!
        val incompatible = insertOffer(maxAmount = product.minAmount - 1)
        val failure = mockMvc.post("/api/v1/pre-approved-offers/$incompatible/apply") {
            header("Authorization", "Bearer $client")
        }.andReturn().response
        assertEquals(409, failure.status)
        assertEquals("ACTIVE", jdbc.queryForObject("select status from pre_approved_offers where id = ?", String::class.java, incompatible))
        assertNull(jdbc.queryForObject("select application_id from pre_approved_offers where id = ?", UUID::class.java, incompatible))

        val overlapping = insertOffer(
            maxAmount = product.maxAmount + 1,
            minTermMonths = product.minTermMonths,
            maxTermMonths = product.maxTermMonths + 1,
        )
        val response = mockMvc.post("/api/v1/pre-approved-offers/$overlapping/apply") {
            header("Authorization", "Bearer $client")
        }.andReturn().response
        assertEquals(201, response.status, response.getContentAsString(Charsets.UTF_8))
        val draft = assertNotNull(applications.find(UUID.fromString(json(response)["id"].asText())))
        assertEquals(product.maxAmount, draft.terms.amount)
        assertEquals(product.maxTermMonths, draft.terms.termMonths)
        assertEquals(overlapping, draft.preApprovedOfferId)
    }

    @Test
    fun `updating draft amount above offer maxAmount detaches preApprovedOfferId and restores offer to ACTIVE`() {
        val offerId = insertOffer(maxAmount = 2_000_000L, minTermMonths = 6, maxTermMonths = 18, rate = BigDecimal("18.25"))

        val applyResp = mockMvc.post("/api/v1/pre-approved-offers/$offerId/apply") {
            header("Authorization", "Bearer $client")
        }.andReturn().response
        assertEquals(201, applyResp.status)
        val appId = UUID.fromString(json(applyResp)["id"].asText())

        // Update within bounds -> stays attached
        val withinBounds = mockMvc.put("/api/v1/applications/$appId") {
            header("Authorization", "Bearer $client")
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"productCode":"WORKING_CAPITAL","purpose":"WORKING_CAPITAL","amount":1500000,"termMonths":12,"declaredRevenue12m":20000000,"bureauConsent":true}"""
        }.andReturn().response
        assertEquals(200, withinBounds.status, withinBounds.getContentAsString(Charsets.UTF_8))
        assertEquals(offerId, applications.find(appId)?.preApprovedOfferId)
        assertEquals("USED", jdbc.queryForObject("select status from pre_approved_offers where id = ?", String::class.java, offerId))

        // Update above maxAmount -> detaches offer and returns it to ACTIVE
        val outOfBounds = mockMvc.put("/api/v1/applications/$appId") {
            header("Authorization", "Bearer $client")
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"productCode":"WORKING_CAPITAL","purpose":"WORKING_CAPITAL","amount":2500000,"termMonths":12,"declaredRevenue12m":20000000,"bureauConsent":true}"""
        }.andReturn().response
        assertEquals(200, outOfBounds.status, outOfBounds.getContentAsString(Charsets.UTF_8))
        assertNull(applications.find(appId)?.preApprovedOfferId)
        assertEquals("ACTIVE", jdbc.queryForObject("select status from pre_approved_offers where id = ?", String::class.java, offerId))
        assertNull(jdbc.queryForObject("select application_id from pre_approved_offers where id = ?", UUID::class.java, offerId))
    }

    @Test
    fun `agent and commission columns persist round-trip through ApplicationRepository`() {
        val agentId = UUID.randomUUID()
        val offerId = insertOffer()
        val now = Instant.now().truncatedTo(ChronoUnit.MICROS)
        val saved = applications.save(
            LoanApplication(
                company = CompanySnapshot(companyId, "000100000004", "ИП Тест", LegalForm.ENTREPRENEUR),
                createdBy = agentId,
                terms = ApplicationTerms(
                    productCode = "WORKING_CAPITAL",
                    purpose = FinancingPurpose.WORKING_CAPITAL,
                    amount = 1_000_000L,
                    termMonths = 12,
                ),
                createdAt = now,
                agentId = agentId,
                agentName = "Агент Иванов",
                preApprovedOfferId = offerId,
                agentCommissionRateBps = 150,
                agentCommissionAmount = 15_000L,
                agentCommissionStatus = CommissionStatus.EXPECTED,
            ),
        )

        val reloaded = assertNotNull(applications.find(saved.id))
        assertEquals(agentId, reloaded.agentId)
        assertEquals("Агент Иванов", reloaded.agentName)
        assertEquals(offerId, reloaded.preApprovedOfferId)
        assertEquals(150, reloaded.agentCommissionRateBps)
        assertEquals(15_000L, reloaded.agentCommissionAmount)
        assertEquals(CommissionStatus.EXPECTED, reloaded.agentCommissionStatus)
    }

    @Test
    fun `CreditPolicy auto-approves at pre-approved rate without manual review when no stop-factors`() {
        val product = Product(
            code = "WORKING_CAPITAL",
            name = "Кредит на пополнение оборота",
            description = "",
            purposes = setOf(FinancingPurpose.WORKING_CAPITAL),
            minAmount = 300_000,
            maxAmount = 30_000_000,
            minTermMonths = 3,
            maxTermMonths = 36,
            baseRate = BigDecimal("20.00"),
            autoApprovalLimit = 5_000_000,
            revolving = false,
        )
        val rules = RuleSet(
            version = 1,
            maxLoad = 0.35,
            revenueMismatch = 0.30,
            ratingCutoffs = mapOf(Rating.A to 80, Rating.B to 65, Rating.C to 50, Rating.D to 35),
            ratingMargins = mapOf(Rating.A to 0.0, Rating.B to 1.5, Rating.C to 3.0, Rating.D to 5.0),
            lowRiskOkved = setOf("01"),
            highRiskOkved = setOf("41"),
            weakAmountShare = 0.5,
            weakRevenueShare = 0.15,
        )
        val terms = ApplicationTerms(
            productCode = "WORKING_CAPITAL",
            purpose = FinancingPurpose.WORKING_CAPITAL,
            amount = 3_000_000L,
            termMonths = 18,
            declaredRevenue12m = 20_000_000L,
            bureauConsent = true,
        )
        val preApproved = PreApprovedOffer(
            id = UUID.randomUUID(),
            companyId = companyId,
            productCode = "WORKING_CAPITAL",
            maxAmount = 3_000_000L,
            minTermMonths = 6,
            maxTermMonths = 24,
            rate = BigDecimal("18.50"),
            validUntil = Instant.now().plus(30, ChronoUnit.DAYS),
            status = PreApprovedOfferStatus.USED,
            createdAt = Instant.now(),
        )
        // Rating C + HIGH risk industry would normally go to MANUAL_REVIEW, but valid pre-approved offer approves automatically at offer.rate
        val assessmentNoStop = Assessment(
            stopFactors = emptyList(),
            factors = emptyList(),
            score = 55,
            rating = Rating.C,
            annualRevenue = 20_000_000L,
            revenueSource = RevenueSource.TAX,
            existingMonthlyPayments = 100_000L,
            businessAgeMonths = 36,
            industryRisk = IndustryRisk.HIGH,
            registryStatusTitle = "Действующее",
        )

        val decision = CreditPolicy.decide(terms, product, listOf(product), assessmentNoStop, rules, preApproved)
        val approve = assertIs<PolicyDecision.Approve>(decision)
        assertEquals(3_000_000L, approve.terms.amount)
        assertEquals(18, approve.terms.termMonths)
        assertEquals(BigDecimal("18.50"), approve.terms.rate)
    }

    @Test
    fun `CreditPolicy still rejects on hard stop-factors even with valid pre-approved offer`() {
        val product = Product(
            code = "WORKING_CAPITAL",
            name = "Кредит на пополнение оборота",
            description = "",
            purposes = setOf(FinancingPurpose.WORKING_CAPITAL),
            minAmount = 300_000,
            maxAmount = 30_000_000,
            minTermMonths = 3,
            maxTermMonths = 36,
            baseRate = BigDecimal("20.00"),
            autoApprovalLimit = 5_000_000,
            revolving = false,
        )
        val rules = RuleSet(
            version = 1,
            maxLoad = 0.35,
            revenueMismatch = 0.30,
            ratingCutoffs = mapOf(Rating.A to 80, Rating.B to 65, Rating.C to 50, Rating.D to 35),
            ratingMargins = mapOf(Rating.A to 0.0, Rating.B to 1.5, Rating.C to 3.0, Rating.D to 5.0),
            lowRiskOkved = setOf("01"),
            highRiskOkved = setOf("41"),
            weakAmountShare = 0.5,
            weakRevenueShare = 0.15,
        )
        val terms = ApplicationTerms(
            productCode = "WORKING_CAPITAL",
            purpose = FinancingPurpose.WORKING_CAPITAL,
            amount = 2_000_000L,
            termMonths = 12,
            declaredRevenue12m = 20_000_000L,
            bureauConsent = true,
        )
        val preApproved = PreApprovedOffer(
            id = UUID.randomUUID(),
            companyId = companyId,
            productCode = "WORKING_CAPITAL",
            maxAmount = 3_000_000L,
            minTermMonths = 6,
            maxTermMonths = 24,
            rate = BigDecimal("18.50"),
            validUntil = Instant.now().plus(30, ChronoUnit.DAYS),
            status = PreApprovedOfferStatus.USED,
            createdAt = Instant.now(),
        )

        for (stopFactor in listOf(StopFactor.SF_STATUS, StopFactor.SF_OVERDUE_90, StopFactor.SF_TOO_YOUNG)) {
            val assessment = Assessment(
                stopFactors = listOf(stopFactor),
                factors = emptyList(),
                score = 85,
                rating = Rating.A,
                annualRevenue = 20_000_000L,
                revenueSource = RevenueSource.TAX,
                existingMonthlyPayments = 0L,
                businessAgeMonths = 48,
                industryRisk = IndustryRisk.LOW,
                registryStatusTitle = "В процессе ликвидации",
            )
            val decision = CreditPolicy.decide(terms, product, listOf(product), assessment, rules, preApproved)
            val reject = assertIs<PolicyDecision.Reject>(decision)
            assertEquals(RejectReason.valueOf(stopFactor.name), reject.reason)
        }
    }

    @Test
    fun `isValidForScoring checks status, applicationId, expiry and bounds`() {
        val now = Instant.parse("2026-11-25T12:00:00Z")
        val appId = UUID.randomUUID()
        val offer = PreApprovedOffer(
            id = UUID.randomUUID(),
            companyId = companyId,
            productCode = "WORKING_CAPITAL",
            maxAmount = 3_000_000L,
            minTermMonths = 6,
            maxTermMonths = 24,
            rate = BigDecimal("18.50"),
            validUntil = now.plus(10, ChronoUnit.DAYS),
            status = PreApprovedOfferStatus.USED,
            applicationId = appId,
            createdAt = now.minus(1, ChronoUnit.DAYS),
        )
        val validTerms = ApplicationTerms(
            productCode = "WORKING_CAPITAL",
            purpose = FinancingPurpose.WORKING_CAPITAL,
            amount = 2_000_000L,
            termMonths = 12,
        )

        assertTrue(offer.isValidForScoring(appId, validTerms, now))
        assertFalse(offer.copy(status = PreApprovedOfferStatus.ACTIVE).isValidForScoring(appId, validTerms, now))
        assertFalse(offer.copy(status = PreApprovedOfferStatus.EXPIRED).isValidForScoring(appId, validTerms, now))
        assertFalse(offer.isValidForScoring(UUID.randomUUID(), validTerms, now))
        assertFalse(offer.copy(applicationId = null).isValidForScoring(appId, validTerms, now))
        assertFalse(offer.isValidForScoring(appId, validTerms, offer.validUntil))
        assertFalse(offer.isValidForScoring(appId, validTerms, offer.validUntil.plusSeconds(1)))
        assertFalse(offer.isValidForScoring(appId, validTerms.copy(amount = 3_000_001L), now))
        assertFalse(offer.isValidForScoring(appId, validTerms.copy(amount = 0L), now))
        assertFalse(offer.isValidForScoring(appId, validTerms.copy(termMonths = 5), now))
        assertFalse(offer.isValidForScoring(appId, validTerms.copy(termMonths = 25), now))
        assertFalse(offer.isValidForScoring(appId, validTerms.copy(productCode = "OVERDRAFT"), now))
    }

    @Test
    fun `applyForAgent creates agent draft with expected commission and restricts to agent portfolio`() {
        val agentId = UUID.randomUUID()
        val offerId = insertOffer(maxAmount = 2_000_000L, minTermMonths = 6, maxTermMonths = 18, rate = BigDecimal("18.00"))

        assertFailsWith<NotFoundException> {
            preApprovedService.applyForAgent(offerId, agentId, listOf(otherCompanyId), "Агент Смирнов")
        }

        val app = preApprovedService.applyForAgent(offerId, agentId, listOf(companyId), "Агент Смирнов")
        assertEquals(agentId, app.agentId)
        assertEquals("Агент Смирнов", app.agentName)
        assertEquals(offerId, app.preApprovedOfferId)
        assertEquals(2_000_000L, app.terms.amount)
        assertEquals(18, app.terms.termMonths)
        assertEquals(CommissionStatus.EXPECTED, app.agentCommissionStatus)
        assertNotNull(app.agentCommissionRateBps)
        assertEquals(2_000_000L * app.agentCommissionRateBps!! / 10_000L, app.agentCommissionAmount)
        assertEquals("USED", jdbc.queryForObject("select status from pre_approved_offers where id = ?", String::class.java, offerId))
    }
}
