package ru.creditcabinet.advice

import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import ru.creditcabinet.advice.domain.AdviceKind
import ru.creditcabinet.application.domain.ActorType
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.assessment.domain.CreditPolicy
import ru.creditcabinet.assessment.domain.Decision
import ru.creditcabinet.assessment.domain.DecisionOutcome
import ru.creditcabinet.assessment.domain.DecisionRepository
import ru.creditcabinet.assessment.domain.EnrichmentStore
import ru.creditcabinet.assessment.domain.Rating
import ru.creditcabinet.assessment.domain.RuleSet
import ru.creditcabinet.assessment.domain.RuleSets
import ru.creditcabinet.assessment.domain.StopFactor
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.LoanMath
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductCatalog
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdviceServiceTest {
    private val applications = mock<ApplicationRepository>()
    private val enrichment = mock<EnrichmentStore>()
    private val decisions = mock<DecisionRepository>()
    private val rules = mock<RuleSets>()
    private val catalog = mock<ProductCatalog>()
    private val service = AdviceService(applications, enrichment, decisions, rules, catalog, Clock.systemUTC())
    private val policy = RuleSet(
        1, 0.35, 0.3, mapOf(Rating.A to 85, Rating.B to 70, Rating.C to 55, Rating.D to 35),
        mapOf(Rating.A to 0.0, Rating.B to 1.0, Rating.C to 3.0, Rating.D to 5.0), emptySet(), emptySet(), 0.5, 0.2,
    )
    private val product = Product("OVERDRAFT", "Овердрафт", "", setOf(FinancingPurpose.CASH_GAP), 100_000, 5_000_000, 12, 12, BigDecimal("19"), 2_000_000, true)
    private val application = LoanApplication(
        company = CompanySnapshot(UUID.randomUUID(), "7700000000", "Компания", LegalForm.LEGAL_ENTITY),
        createdBy = UUID.randomUUID(),
        terms = ApplicationTerms(
            product.code,
            FinancingPurpose.CASH_GAP,
            amount = 1_000_000,
            termMonths = 12,
            declaredRevenue12m = 3_600_000,
            declaredMonthlyDebtPayments = 0,
            bureauConsent = true,
        ),
        status = ApplicationStatus.MANUAL_REVIEW,
        createdAt = Instant.parse("2026-10-25T10:00:00Z"),
    )

    @BeforeTest
    fun setup() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(TokenClaims(UUID.randomUUID(), "UNDERWRITER", "Проверка", "test@example.test"), null)
        whenever(applications.find(application.id)).thenReturn(application)
        whenever(enrichment.load(application.id)).thenReturn(emptyMap())
        whenever(rules.current()).thenReturn(policy)
        whenever(rules.find(1)).thenReturn(policy)
        whenever(catalog.version(any(), any())).thenReturn(product)
    }

    @AfterTest
    fun cleanup() = SecurityContextHolder.clearContext()

    private fun decision(rating: Rating?, version: Int = 1, stops: List<StopFactor> = emptyList()) = Decision(
        applicationId = application.id, ruleSetVersion = version, decidedByType = ActorType.SYSTEM, decidedBy = null,
        score = 70, rating = rating, stopFactors = stops, factors = emptyList(), outcome = DecisionOutcome.MANUAL_REVIEW,
        reasonCode = null, manualReasons = emptyList(), recommendation = null, inputs = emptyMap(), comment = null, createdAt = application.createdAt,
    )

    @Test
    fun `E without a fictitious margin returns an explained stop with both pinned rule versions`() {
        assertFalse(Rating.E in policy.ratingMargins)
        whenever(rules.find(2)).thenReturn(policy.copy(version = 2, maxLoad = 0.4))
        for (version in listOf(1, 2)) {
            whenever(decisions.latestAutomatic(application.id)).thenReturn(decision(Rating.E, version))
            val view = service.advice(application.id)
            assertEquals(2, view.modelVersion)
            assertTrue(view.items.any { it.code == "RATING_E" && it.kind == AdviceKind.STOP && it.because.isNotEmpty() })
            assertTrue(view.items.none { it.kind == AdviceKind.OPTION })
            verify(rules).find(version)
        }
    }

    @Test
    fun `stop factors return explanations without payment options`() {
        whenever(decisions.latestAutomatic(application.id)).thenReturn(decision(Rating.B, stops = listOf(StopFactor.SF_STATUS)))
        val view = service.advice(application.id)
        assertTrue(view.items.any { it.code == "STOP" && it.kind == AdviceKind.STOP })
        assertTrue(view.items.none { it.kind == AdviceKind.OPTION })
    }

    @Test
    fun `every terminal status is readable without recalculating issue rates`() {
        for (status in ApplicationStatus.entries.filter { it.terminal }) {
            whenever(applications.find(application.id)).thenReturn(application.copy(status = status))
            whenever(decisions.latestAutomatic(application.id)).thenReturn(decision(Rating.E))
            val view = service.advice(application.id)
            assertEquals("TERMINAL", view.items.single().code)
            assertEquals(if (status == ApplicationStatus.REJECTED) AdviceKind.STOP else AdviceKind.INFO, view.items.single().kind)
            assertTrue(view.verdict.isNotBlank())
            assertEquals(0, view.rulesFired)
        }
    }

    @Test
    fun `annuity overdraft and card retain A to D or unknown rating margins and load results`() {
        for ((code, revolving) in listOf("CREDIT" to false, "OVERDRAFT" to true, "CARD" to true)) {
            val selected = product.copy(code = code, revolving = revolving)
            whenever(catalog.version(any(), any())).thenReturn(selected)
            for (rating in listOf(Rating.A, Rating.B, Rating.C, Rating.D, null)) {
                whenever(decisions.latestAutomatic(application.id)).thenReturn(if (rating == null) null else decision(rating))
                val payment = LoanMath.calculate(1_000_000, CreditPolicy.rate(selected, rating, policy), 12, revolving).monthlyPayment
                val budget = policy.maxLoad * (3_600_000 / 12.0)
                val view = service.advice(application.id)
                assertEquals(payment.toDouble() > budget, view.items.any { it.code == "LOAD" }, "$code $rating")
                view.items.filter { it.code == "LOAD" }.forEach { assertTrue(it.because.any { text -> "Платёж по запрошенным условиям" in text }) }
            }
        }
    }
}
