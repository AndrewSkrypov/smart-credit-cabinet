package ru.creditcabinet.assessment.contract.v1

import ru.creditcabinet.assessment.domain.BankInfo
import ru.creditcabinet.assessment.domain.BureauInfo
import ru.creditcabinet.assessment.domain.RegistryInfo
import ru.creditcabinet.assessment.domain.SourceData
import ru.creditcabinet.assessment.domain.SourceKind
import ru.creditcabinet.assessment.domain.TaxInfo
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

private val allSources = SourceKind.entries.toSet()

enum class AssessmentOutcomeV1 { ASSESSED, INCOMPLETE }

data class AssessmentCompletedV1(
    val cycle: AssessmentCycleV1,
    val receipts: Map<SourceKind, Instant>,
    val facts: NormalizedFactsV1,
    val outcome: AssessmentOutcomeV1,
    val missingSources: Set<SourceKind>,
    val completedAt: Instant,
    val assessment: AssessmentSnapshotV1?,
    val policy: PolicyV1?,
    val appliedPreApprovalId: UUID?,
    val protocolVersion: Int = ASSESSMENT_PROTOCOL_V1,
) {
    init {
        require(protocolVersion == ASSESSMENT_PROTOCOL_V1) { "Unsupported assessment protocol version" }
        require(receipts.keys == facts.bySource().keys) { "Source receipts and facts differ" }
        require(receipts.values.all { it <= completedAt }) { "Source receipt follows completion" }
        require(missingSources == allSources - receipts.keys) { "Missing sources do not complement receipts" }
        when (outcome) {
            AssessmentOutcomeV1.ASSESSED -> {
                require(missingSources.isEmpty() && assessment != null && policy != null) { "Assessed result is incomplete" }
                require(appliedPreApprovalId == null || appliedPreApprovalId == cycle.candidatePreApprovalId) {
                    "Applied preapproval does not match cycle candidate"
                }
                require(appliedPreApprovalId == null || (policy.kind == PolicyKindV1.APPROVE && assessment.stopFactors.isEmpty())) {
                    "Applied preapproval requires an approval without stop factors"
                }
            }
            AssessmentOutcomeV1.INCOMPLETE -> require(
                missingSources.isNotEmpty() && assessment == null && policy == null && appliedPreApprovalId == null,
            ) { "Incomplete collection cannot contain an assessment, a policy decision, or an applied preapproval" }
        }
    }

    /** Checks the result against the exact command persisted by Credit. */
    fun validateAgainst(request: AssessmentRequestedV1) {
        require(cycle == request.cycle) { "Result belongs to a different assessment cycle or snapshot" }
        require(completedAt >= request.startsAt) { "Result completed before cycle start" }
        require(facts.registry == null || facts.registry.inn == request.inn) { "Registry fact belongs to a different company" }
        require(facts.bureau == null || request.bureauConsent != null) { "Bureau fact requires application consent" }
        require(receipts.values.all { it < request.deadline }) { "Source receipt did not meet collection deadline" }
        require(receipts.all { (source, time) -> time >= request.startsAt || (source == SourceKind.BANK && request.bankPrefetch != null) }) {
            "Source receipt predates cycle start"
        }
        request.bankPrefetch?.let { prefetch ->
            require(receipts[SourceKind.BANK] == prefetch.receivedAt && facts.bank == prefetch.fact) {
                "Bank fact or receipt differs from the prefetched snapshot"
            }
        }
        val offeredPolicy = policy?.recommendation ?: policy
        val offer = offeredPolicy?.terms
        if (offer != null) {
            val product = if (offer.productCode == request.selectedProduct.ref.code) {
                request.selectedProduct
            } else {
                requireNotNull(request.catalog.find { it.ref.code == offer.productCode }) { "Offer product is absent from the frozen catalog" }
            }
            require(offer.amount in product.minAmount..product.maxAmount && offer.termMonths in product.minTermMonths..product.maxTermMonths) {
                "Offer falls outside frozen product bounds"
            }
            require(
                offeredPolicy.kind != PolicyKindV1.APPROVE ||
                    (offer.productCode == request.terms.productCode && offer.amount == request.terms.amount && offer.termMonths == request.terms.termMonths),
            ) { "Approval changes the requested product, amount or term" }
        }
        if (appliedPreApprovalId != null) {
            val candidate = requireNotNull(request.candidatePreApproval) { "Applied preapproval requires a frozen candidate" }
            require(
                completedAt < candidate.validUntil &&
                    request.terms.productCode == candidate.productCode &&
                    request.terms.amount <= candidate.maxAmount &&
                    request.terms.termMonths in candidate.minTermMonths..candidate.maxTermMonths &&
                    offer != null &&
                    offer.productCode == candidate.productCode &&
                    offer.amount == request.terms.amount &&
                    offer.termMonths == request.terms.termMonths &&
                    offer.rate.compareTo(candidate.rate) == 0,
            ) { "Applied preapproval does not match its frozen terms or validity" }
        }
    }
}

data class NormalizedFactsV1(
    val bank: BankInfo? = null,
    val registry: RegistryInfo? = null,
    val tax: TaxInfo? = null,
    val bureau: BureauInfo? = null,
) {
    fun bySource(): Map<SourceKind, Any> = buildMap {
        bank?.let { put(SourceKind.BANK, it) }
        registry?.let { put(SourceKind.REGISTRY, it) }
        tax?.let { put(SourceKind.TAX, it) }
        bureau?.let { put(SourceKind.BUREAU, it) }
    }

    fun complete(): SourceData? =
        if (registry != null && tax != null && bureau != null && bank != null) SourceData(registry, tax, bureau, bank) else null
}

enum class StopFactorV1 { SF_STATUS, SF_UNRELIABLE, SF_OVERDUE_90, SF_TOO_YOUNG }

enum class FactorV1 { AGE, TURNOVER_STABILITY, DEBT_LOAD, PAYMENT_DISCIPLINE, CREDIT_ACTIVITY, TAX_DISCIPLINE, INDUSTRY_RISK, REQUEST_SIZE }

enum class RevenueSourceV1 { TAX, TURNOVER, DECLARED }

enum class IndustryRiskV1 { LOW, MEDIUM, HIGH }

data class FactorScoreV1(
    val factor: FactorV1,
    val title: String,
    val value: String,
    val points: Int,
    val maxPoints: Int,
    val hint: String?,
) {
    init {
        require(maxPoints in 1..100 && points in 0..maxPoints) { "Invalid factor points" }
    }
}

data class AssessmentSnapshotV1(
    val stopFactors: List<StopFactorV1>,
    val factors: List<FactorScoreV1>,
    val score: Int,
    val rating: RatingV1,
    val annualRevenue: Long,
    val revenueSource: RevenueSourceV1,
    val existingMonthlyPayments: Long,
    val businessAgeMonths: Long,
    val industryRisk: IndustryRiskV1,
    val registryStatusTitle: String,
) {
    init {
        require(stopFactors.toSet().size == stopFactors.size) { "Duplicate stop factor" }
        require(factors.size == FactorV1.entries.size && factors.map { it.factor }.toSet().size == factors.size) {
            "A full assessment requires every factor exactly once"
        }
        require(score in 0..100 && score == factors.sumOf { it.points }) { "Assessment score does not match factor points" }
    }
}

enum class PolicyKindV1 { APPROVE, COUNTER_OFFER, REJECT, MANUAL_REVIEW }

enum class RejectReasonV1 { SF_STATUS, SF_UNRELIABLE, SF_OVERDUE_90, SF_TOO_YOUNG, R_LOAD, R_SCORE, R_MANUAL }

enum class ManualReasonV1 { RATING_C, AMOUNT_ABOVE_AUTO_LIMIT, REVENUE_MISMATCH, HIGH_RISK_INDUSTRY, SOURCE_UNAVAILABLE }

data class OfferTermsV1(
    val productCode: String,
    val amount: Long,
    val termMonths: Int,
    val rate: BigDecimal,
    val monthlyPayment: BigDecimal,
    val totalPayment: BigDecimal,
) {
    init {
        require(productCode.isNotBlank() && amount > 0 && termMonths > 0) { "Invalid offer product, amount or term" }
        require(rate.signum() > 0 && monthlyPayment.signum() > 0 && totalPayment.signum() > 0) { "Invalid offer rate or payment" }
    }
}

/** The explicit wire discriminator makes each policy variant independent of Kotlin implementation types. */
data class PolicyV1(
    val kind: PolicyKindV1,
    val terms: OfferTermsV1? = null,
    val rejectReason: RejectReasonV1? = null,
    val notes: List<String> = emptyList(),
    val manualReasons: List<ManualReasonV1> = emptyList(),
    val recommendation: PolicyV1? = null,
) {
    init {
        when (kind) {
            PolicyKindV1.APPROVE, PolicyKindV1.COUNTER_OFFER ->
                require(terms != null && rejectReason == null && manualReasons.isEmpty() && recommendation == null) {
                    "Offer policy requires terms and cannot contain rejection or manual review"
                }
            PolicyKindV1.REJECT -> require(terms == null && rejectReason != null && manualReasons.isEmpty() && recommendation == null) {
                "Reject policy requires a reason and cannot contain offer terms or manual review"
            }
            PolicyKindV1.MANUAL_REVIEW -> require(
                terms == null && rejectReason == null && manualReasons.isNotEmpty() && recommendation?.kind != PolicyKindV1.MANUAL_REVIEW,
            ) { "Manual review requires reasons and may recommend an approval, counteroffer, or rejection" }
        }
    }
}
