package ru.creditcabinet.assessment.domain

import ru.creditcabinet.assessment.contract.v1.AssessmentRequestedV1
import ru.creditcabinet.assessment.contract.v1.AssessmentSnapshotV1
import ru.creditcabinet.assessment.contract.v1.FactorScoreV1
import ru.creditcabinet.assessment.contract.v1.FactorV1
import ru.creditcabinet.assessment.contract.v1.IndustryRiskV1
import ru.creditcabinet.assessment.contract.v1.ManualReasonV1
import ru.creditcabinet.assessment.contract.v1.OfferTermsV1
import ru.creditcabinet.assessment.contract.v1.PolicyKindV1
import ru.creditcabinet.assessment.contract.v1.PolicyV1
import ru.creditcabinet.assessment.contract.v1.ProductSnapshotV1
import ru.creditcabinet.assessment.contract.v1.RatingV1
import ru.creditcabinet.assessment.contract.v1.RejectReasonV1
import ru.creditcabinet.assessment.contract.v1.RevenueSourceV1
import ru.creditcabinet.assessment.contract.v1.RuleSnapshotV1
import ru.creditcabinet.assessment.contract.v1.StopFactorV1
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

data class AssessmentEvaluation(val assessment: AssessmentSnapshotV1, val policy: PolicyV1, val appliedPreApprovalId: UUID?)

class SnapshotEvaluation(payments: AssessmentPaymentCalculator) {
    private val scorecard = AssessmentScorecard(payments)
    private val policy = AssessmentPolicy(payments)

    fun evaluate(request: AssessmentRequestedV1, sources: SourceData, at: Instant): AssessmentEvaluation {
        val terms = request.terms.let { AssessmentTerms(it.productCode, it.amount, it.termMonths, it.declaredRevenue12m) }
        val product = request.selectedProduct.toDomain()
        val rules = request.rules.toDomain()
        val assessment = scorecard.assess(terms, product, sources, rules, at.atZone(ZONE).toLocalDate())
        val candidate = request.candidatePreApproval?.takeIf { at < it.validUntil }
        val preApproval = candidate?.let { AssessmentPreApproval(it.productCode, it.maxAmount, it.minTermMonths, it.maxTermMonths, it.rate) }
        val decision = policy.decide(terms, product, request.catalog.map(ProductSnapshotV1::toDomain), assessment, rules, preApproval)
        val applied = candidate?.id.takeIf { assessment.stopFactors.isEmpty() && preApproval?.isWithinBounds(terms) == true }
        return AssessmentEvaluation(assessment.toSnapshot(), decision.toSnapshot(), applied)
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Moscow")
    }
}

private fun ProductSnapshotV1.toDomain() =
    AssessmentProduct(ref.code, name, minAmount, maxAmount, minTermMonths, maxTermMonths, baseRate, autoApprovalLimit, revolving)

private fun RuleSnapshotV1.toDomain() = RuleSet(
    version, maxLoad, revenueMismatch, ratingCutoffs.mapKeys { Rating.valueOf(it.key.name) }, ratingMargins.mapKeys { Rating.valueOf(it.key.name) },
    lowRiskOkved, highRiskOkved, weakAmountShare, weakRevenueShare,
)

fun RuleSet.toSnapshot() = RuleSnapshotV1(
    version, maxLoad, revenueMismatch, ratingCutoffs.mapKeys { RatingV1.valueOf(it.key.name) }, ratingMargins.mapKeys { RatingV1.valueOf(it.key.name) },
    lowRiskOkved, highRiskOkved, weakAmountShare, weakRevenueShare,
)

private fun Assessment.toSnapshot() = AssessmentSnapshotV1(
    stopFactors.map { StopFactorV1.valueOf(it.name) },
    factors.map { FactorScoreV1(FactorV1.valueOf(it.factor.name), it.title, it.value, it.points, it.maxPoints, it.hint) },
    score, RatingV1.valueOf(rating.name), annualRevenue, RevenueSourceV1.valueOf(revenueSource.name),
    existingMonthlyPayments, businessAgeMonths, IndustryRiskV1.valueOf(industryRisk.name), registryStatusTitle,
)

fun AssessmentSnapshotV1.toDomain() = Assessment(
    stopFactors.map { StopFactor.valueOf(it.name) },
    factors.map { FactorScore(Factor.valueOf(it.factor.name), it.title, it.value, it.points, it.maxPoints, it.hint) },
    score, Rating.valueOf(rating.name), annualRevenue, RevenueSource.valueOf(revenueSource.name),
    existingMonthlyPayments, businessAgeMonths, IndustryRisk.valueOf(industryRisk.name), registryStatusTitle,
)

private fun AssessmentOfferTerms.toSnapshot() = OfferTermsV1(productCode, amount, termMonths, rate, monthlyPayment, totalPayment)

fun OfferTermsV1.toDomain() = AssessmentOfferTerms(productCode, amount, termMonths, rate, monthlyPayment, totalPayment)

private fun AssessmentPolicyDecision.toSnapshot(): PolicyV1 = when (this) {
    is AssessmentPolicyDecision.Approve -> PolicyV1(PolicyKindV1.APPROVE, terms.toSnapshot())
    is AssessmentPolicyDecision.Counter -> PolicyV1(PolicyKindV1.COUNTER_OFFER, terms.toSnapshot(), notes = changes)
    is AssessmentPolicyDecision.Reject -> PolicyV1(PolicyKindV1.REJECT, rejectReason = RejectReasonV1.valueOf(reason.name), notes = explanation)
    is AssessmentPolicyDecision.Manual -> PolicyV1(
        PolicyKindV1.MANUAL_REVIEW,
        manualReasons = reasons.map { ManualReasonV1.valueOf(it.name) },
        recommendation = recommendation?.toSnapshot(),
    )
}

fun PolicyV1.toDomain(): AssessmentPolicyDecision = when (kind) {
    PolicyKindV1.APPROVE -> AssessmentPolicyDecision.Approve(checkNotNull(terms).toDomain())
    PolicyKindV1.COUNTER_OFFER -> AssessmentPolicyDecision.Counter(checkNotNull(terms).toDomain(), notes)
    PolicyKindV1.REJECT -> AssessmentPolicyDecision.Reject(RejectReason.valueOf(checkNotNull(rejectReason).name), notes)
    PolicyKindV1.MANUAL_REVIEW -> AssessmentPolicyDecision.Manual(manualReasons.map { ManualReason.valueOf(it.name) }, recommendation?.toDomain())
}
