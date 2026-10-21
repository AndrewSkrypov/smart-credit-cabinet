package ru.creditcabinet.assessment

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.assessment.domain.Assessment
import ru.creditcabinet.assessment.domain.BankInfo
import ru.creditcabinet.assessment.domain.BureauInfo
import ru.creditcabinet.assessment.domain.BureauObligation
import ru.creditcabinet.assessment.domain.CreditPolicy
import ru.creditcabinet.assessment.domain.ManualReason
import ru.creditcabinet.assessment.domain.PolicyDecision
import ru.creditcabinet.assessment.domain.Rating
import ru.creditcabinet.assessment.domain.RegistryInfo
import ru.creditcabinet.assessment.domain.RejectReason
import ru.creditcabinet.assessment.domain.RuleSet
import ru.creditcabinet.assessment.domain.Scorecard
import ru.creditcabinet.assessment.domain.SourceData
import ru.creditcabinet.assessment.domain.TaxInfo
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.Product
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The scenario companies of the simulators (external-sim dataset) through the scorecard and the
 * credit policy: each must give the outcome promised in docs/integrations.md.
 */
class ScenarioPolicyTest {
    private val today = LocalDate.of(2026, 10, 21)
    private val rules = RuleSet(
        version = 1,
        maxLoad = 0.35,
        revenueMismatch = 0.30,
        ratingCutoffs = mapOf(Rating.A to 80, Rating.B to 65, Rating.C to 50, Rating.D to 35),
        ratingMargins = mapOf(Rating.A to 0.0, Rating.B to 1.5, Rating.C to 3.0, Rating.D to 5.0),
        lowRiskOkved = setOf("01", "10", "31", "62"),
        highRiskOkved = setOf("41", "42", "43", "64", "68"),
        weakAmountShare = 0.5,
        weakRevenueShare = 0.15,
    )

    private fun product(
        code: String,
        purposes: Set<FinancingPurpose>,
        min: Long,
        max: Long,
        minTerm: Int,
        maxTerm: Int,
        rate: String,
        auto: Long,
        revolving: Boolean,
    ) = Product(code, code, "", purposes, min, max, minTerm, maxTerm, BigDecimal(rate), auto, revolving)

    private val products = listOf(
        product("OVERDRAFT", setOf(FinancingPurpose.CASH_GAP), 100_000, 5_000_000, 12, 12, "19", 2_000_000, true),
        product("WORKING_CAPITAL", setOf(FinancingPurpose.WORKING_CAPITAL), 300_000, 30_000_000, 3, 36, "20", 5_000_000, false),
        product("BUSINESS_CARD", setOf(FinancingPurpose.CURRENT_EXPENSES), 50_000, 1_000_000, 12, 12, "25", 1_000_000, true),
    ).associateBy { it.code }

    private val dataset: Map<String, JsonNode> =
        jacksonObjectMapper().readTree(File("../external-sim/src/main/resources/dataset/companies.json")).associateBy { it["inn"].asText() }

    /** The same data the simulators answer with, turnovers included (a sine with the dataset's variation). */
    private fun sourceData(company: JsonNode): SourceData {
        val fns = company["fns"].takeIf { !it.isNull }
        val cv = company["bank"]["turnoverCv"].takeIf { !it.isNull }?.asDouble()
        val client = company["bank"]["client"].asBoolean()
        val inflows = if (client && cv != null && fns != null) {
            val mean = fns["revenue"].asLong() * 0.9 / 12
            val phase = (company["inn"].asText().takeLast(3).toInt() % 12) * PI / 6
            (0 until 12).map { k -> (mean * (1 + cv * sqrt(2.0) * sin(2 * PI * k / 12 + phase))).roundToLong() }
        } else {
            emptyList()
        }
        val obligations = company["bureau"]["obligations"].map {
            BureauObligation(
                it["creditor"].asText(),
                it["type"].asText(),
                it["limit"].asLong(),
                it["balance"].asLong(),
                it["monthlyPayment"].asLong(),
                it["currentOverdueDays"].asInt(),
            )
        }
        return SourceData(
            registry = RegistryInfo(
                inn = company["inn"].asText(),
                ogrn = company["ogrn"].asText(),
                fullName = company["fullName"].asText(),
                shortName = company["shortName"].asText(),
                status = company["status"].asText(),
                statusTitle = "",
                registrationDate = today.minusMonths(company["registeredMonthsAgo"].asLong()),
                okvedCode = company["okved"]["code"].asText(),
                okvedName = company["okved"]["name"].asText(),
                directorName = company["director"]["fullName"].asText(),
                address = company["address"].asText(),
                unreliableAddress = company["unreliableAddress"].asBoolean(),
                unreliableDirector = company["unreliableDirector"].asBoolean(),
            ),
            tax = TaxInfo(
                hasReports = fns != null,
                reportingYear = fns?.let { today.year - 1 },
                revenue = fns?.get("revenue")?.asLong(),
                expenses = fns?.get("expenses")?.asLong(),
                taxDebt = fns?.get("taxDebt")?.asLong() ?: 0,
                averageHeadcount = fns?.get("averageHeadcount")?.asInt(),
                taxRegime = fns?.get("taxRegime")?.asText(),
                massAddress = false,
            ),
            bureau = BureauInfo(
                obligations = obligations,
                monthlyPaymentsTotal = obligations.sumOf { it.monthlyPayment },
                currentOverdueDaysMax = obligations.maxOfOrNull { it.currentOverdueDays } ?: 0,
                worstOverdueDays24m = company["bureau"]["worstOverdueDays24m"].asInt(),
                inquiries30d = company["bureau"]["inquiries30d"].asInt(),
            ),
            bank = BankInfo(client, null, inflows),
        )
    }

    private fun run(inn: String): Pair<Assessment, PolicyDecision> {
        val company = dataset.getValue(inn)
        val request = company["scenario"]["request"]
        val product = products.getValue(request["productCode"].asText())
        val terms = ApplicationTerms(
            productCode = product.code,
            purpose = FinancingPurpose.valueOf(request["purpose"].asText()),
            amount = request["amount"].asLong(),
            termMonths = request["termMonths"].asInt(),
            declaredRevenue12m = request["declaredRevenue12m"].asLong(),
            bureauConsent = true,
        )
        val assessment = Scorecard.assess(terms, product, sourceData(company), rules, today)
        return assessment to CreditPolicy.decide(terms, product, products.values.toList(), assessment, rules)
    }

    @Test
    fun `Kotova - rating A, approved on the requested terms`() {
        val (assessment, decision) = run("000100000004")
        assertEquals(91, assessment.score)
        assertEquals(Rating.A, assessment.rating)
        val approve = assertIs<PolicyDecision.Approve>(decision)
        assertEquals(1_000_000, approve.terms.amount)
        assertEquals(BigDecimal("20.00"), approve.terms.rate)
    }

    @Test
    fun `Northern Furniture - rating B with a heavy load gets a smaller amount for a longer term`() {
        val (assessment, decision) = run("0001001014")
        assertEquals(76, assessment.score)
        val counter = assertIs<PolicyDecision.Counter>(decision)
        assertEquals(36, counter.terms.termMonths)
        assertEquals(2_630_000, counter.terms.amount)
        assertEquals(BigDecimal("21.50"), counter.terms.rate)
        // Payments with the new loan stay within 35% of the monthly revenue of 3 000 000.
        assertTrue(counter.terms.monthlyPayment.toDouble() + 950_000 <= 0.35 * 3_000_000)
    }

    @Test
    fun `Vector Logistics - rating C goes to an underwriter with a recommendation`() {
        val (assessment, decision) = run("0001001021")
        assertEquals(58, assessment.score)
        val manual = assertIs<PolicyDecision.Manual>(decision)
        assertEquals(listOf(ManualReason.RATING_C), manual.reasons)
        assertIs<PolicyDecision.Approve>(manual.recommendation)
    }

    @Test
    fun `stop factors refuse whatever the score`() {
        assertEquals(RejectReason.SF_STATUS, assertIs<PolicyDecision.Reject>(run("0001001039").second).reason)
        assertEquals(RejectReason.SF_OVERDUE_90, assertIs<PolicyDecision.Reject>(run("000100020145").second).reason)
        assertEquals(RejectReason.SF_TOO_YOUNG, assertIs<PolicyDecision.Reject>(run("000100020392").second).reason)
    }

    @Test
    fun `Belova - rating E is refused with the weakest factors explained`() {
        val (assessment, decision) = run("000100020219")
        assertEquals(17, assessment.score)
        val reject = assertIs<PolicyDecision.Reject>(decision)
        assertEquals(RejectReason.R_SCORE, reject.reason)
        assertEquals(4, reject.explanation.size, "the reason and three factors")
    }

    @Test
    fun `Technopark - rating A without turnover data, approved`() {
        val (assessment, decision) = run("0001001046")
        assertEquals(82, assessment.score)
        assertIs<PolicyDecision.Approve>(decision)
    }

    @Test
    fun `StroyGrad - a good rating in construction still goes to an underwriter`() {
        val manual = assertIs<PolicyDecision.Manual>(run("0001001060").second)
        assertEquals(listOf(ManualReason.HIGH_RISK_INDUSTRY), manual.reasons)
    }

    @Test
    fun `a declared revenue far from the tax service's sends the application to an underwriter`() {
        val company = dataset.getValue("000100000004")
        val product = products.getValue("WORKING_CAPITAL")
        val terms =
            ApplicationTerms(
                "WORKING_CAPITAL",
                FinancingPurpose.WORKING_CAPITAL,
                amount = 1_000_000,
                termMonths = 12,
                declaredRevenue12m = 30_000_000,
                bureauConsent = true,
            )
        val assessment = Scorecard.assess(terms, product, sourceData(company), rules, today)
        val manual = assertIs<PolicyDecision.Manual>(CreditPolicy.decide(terms, product, products.values.toList(), assessment, rules))
        assertEquals(listOf(ManualReason.REVENUE_MISMATCH), manual.reasons)
    }
}
