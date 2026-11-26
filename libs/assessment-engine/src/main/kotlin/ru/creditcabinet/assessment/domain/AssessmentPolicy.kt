package ru.creditcabinet.assessment.domain

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/** Terms the bank is ready to lend on. */
data class AssessmentOfferTerms(
    val productCode: String,
    val amount: Long,
    val termMonths: Int,
    val rate: BigDecimal,
    val monthlyPayment: BigDecimal,
    val totalPayment: BigDecimal,
)

/** Why the rules want a person to decide (docs/process-model.md, section 7). */
enum class ManualReason(val title: String) {
    RATING_C("Рейтинг C — серая зона"),
    AMOUNT_ABOVE_AUTO_LIMIT("Сумма выше порога автоматического решения по продукту"),
    REVENUE_MISMATCH("Заявленная выручка расходится с данными ФНС больше допустимого"),
    HIGH_RISK_INDUSTRY("Отрасль высокого риска"),
    SOURCE_UNAVAILABLE("Источник данных не ответил за отведённое время"),
}

/** Refusal codes the client sees with a plain explanation. */
enum class RejectReason(val clientText: String) {
    SF_STATUS(StopFactor.SF_STATUS.clientText),
    SF_UNRELIABLE(StopFactor.SF_UNRELIABLE.clientText),
    SF_OVERDUE_90(StopFactor.SF_OVERDUE_90.clientText),
    SF_TOO_YOUNG(StopFactor.SF_TOO_YOUNG.clientText),
    R_LOAD("Платёж будет слишком большим для текущей выручки"),
    R_SCORE("Сейчас риск выше, чем допускает продукт"),
    R_MANUAL("Решение специалиста банка"),
}

sealed interface AssessmentPolicyDecision {
    /** The requested terms, at the rate for the rating. */
    data class Approve(val terms: AssessmentOfferTerms) : AssessmentPolicyDecision

    /** Other terms the bank can lend on: a longer term, a smaller amount or another product. */
    data class Counter(val terms: AssessmentOfferTerms, val changes: List<String>) : AssessmentPolicyDecision

    data class Reject(val reason: RejectReason, val explanation: List<String>) : AssessmentPolicyDecision

    /** A person decides; [recommendation] is what the rules would do without the reasons. */
    data class Manual(val reasons: List<ManualReason>, val recommendation: AssessmentPolicyDecision?) : AssessmentPolicyDecision
}

/**
 * The credit logic on top of the score: the same rating can mean different terms for different
 * products and amounts. Order of docs/process-model.md, section 7: stop factors, rating E,
 * reasons for manual review, then terms by rating.
 */
class AssessmentPolicy(private val payments: AssessmentPaymentCalculator) {
    private val roundTo = 10_000L

    fun decide(
        terms: AssessmentTerms,
        product: AssessmentProduct,
        products: List<AssessmentProduct>,
        assessment: Assessment,
        rules: RuleSet,
        preApprovedOffer: AssessmentPreApproval? = null,
    ): AssessmentPolicyDecision {
        assessment.stopFactors.firstOrNull()?.let { stop ->
            return AssessmentPolicyDecision.Reject(RejectReason.valueOf(stop.name), assessment.stopFactorTexts())
        }
        if (preApprovedOffer != null && preApprovedOffer.isWithinBounds(terms)) {
            return AssessmentPolicyDecision.Approve(
                offerTerms(product, terms.amount, terms.termMonths, preApprovedOffer.rate),
            )
        }
        if (assessment.rating == Rating.E) return rejectByScore(assessment)
        val reasons = buildList {
            if (assessment.rating == Rating.C) add(ManualReason.RATING_C)
            if (terms.amount > product.autoApprovalLimit) add(ManualReason.AMOUNT_ABOVE_AUTO_LIMIT)
            if (revenueMismatch(terms, assessment, rules)) add(ManualReason.REVENUE_MISMATCH)
            if (assessment.industryRisk == IndustryRisk.HIGH) add(ManualReason.HIGH_RISK_INDUSTRY)
        }
        val byRating = byRating(terms, product, products, assessment, rules)
        return if (reasons.isEmpty()) byRating else AssessmentPolicyDecision.Manual(reasons, byRating)
    }

    fun rate(product: AssessmentProduct, rating: Rating?, rules: RuleSet): BigDecimal {
        // Without a rating (a source did not answer) the underwriter lends at the margin of C.
        val margin = rules.ratingMargins.getValue(rating ?: Rating.C)
        return product.baseRate.add(BigDecimal.valueOf(margin)).setScale(2, RoundingMode.HALF_UP)
    }

    private fun revenueMismatch(terms: AssessmentTerms, assessment: Assessment, rules: RuleSet): Boolean {
        val declared = terms.declaredRevenue12m ?: return false
        if (assessment.revenueSource != RevenueSource.TAX || assessment.annualRevenue <= 0) return false
        return abs(declared - assessment.annualRevenue).toDouble() / assessment.annualRevenue > rules.revenueMismatch
    }

    private fun byRating(
        terms: AssessmentTerms,
        product: AssessmentProduct,
        products: List<AssessmentProduct>,
        assessment: Assessment,
        rules: RuleSet,
    ): AssessmentPolicyDecision {
        val rating = if (assessment.rating == Rating.C) Rating.B else assessment.rating
        val rate = rate(product, assessment.rating, rules)
        val budget = rules.maxLoad * assessment.monthlyRevenue - assessment.existingMonthlyPayments
        return when (rating) {
            Rating.A, Rating.B -> strong(terms, product, products, assessment, rules, rate, budget)
            else -> weak(terms, product, assessment, rules, rate, budget)
        }
    }

    /** A and B: the requested terms if the load allows, otherwise a longer term, a smaller amount, another product. */
    private fun strong(
        terms: AssessmentTerms,
        product: AssessmentProduct,
        products: List<AssessmentProduct>,
        assessment: Assessment,
        rules: RuleSet,
        rate: BigDecimal,
        budget: Double,
    ): AssessmentPolicyDecision {
        if (fits(product, terms.amount, terms.termMonths, rate, budget)) {
            return AssessmentPolicyDecision.Approve(offerTerms(product, terms.amount, terms.termMonths, rate))
        }
        val longest = product.maxTermMonths
        if (longest > terms.termMonths && fits(product, terms.amount, longest, rate, budget)) {
            return AssessmentPolicyDecision.Counter(
                offerTerms(product, terms.amount, longest, rate),
                listOf("срок увеличен до $longest мес., чтобы снизить платёж"),
            )
        }
        maxAmount(product, longest, rate, budget)?.takeIf { it >= product.minAmount }?.let { amount ->
            return AssessmentPolicyDecision.Counter(
                offerTerms(product, amount, longest, rate),
                buildList {
                    add("сумма уменьшена до ${rub(amount)}, чтобы платежи не превышали ${(rules.maxLoad * 100).roundToInt()}% выручки")
                    if (longest != terms.termMonths) add("срок увеличен до $longest мес.")
                },
            )
        }
        // A smaller product: an overdraft or a card limit can still fit where a loan does not.
        products.filter { it.code != product.code && it.revolving }.sortedBy { it.minAmount }.forEach { other ->
            val otherRate = rate(other, assessment.rating, rules)
            val amount = maxAmount(other, other.maxTermMonths, otherRate, budget)?.coerceAtMost(minOf(other.maxAmount, terms.amount))
            if (amount != null && amount >= other.minAmount) {
                return AssessmentPolicyDecision.Counter(
                    offerTerms(other, amount, other.maxTermMonths, otherRate),
                    listOf("вместо «${product.name}» — «${other.name}» на ${rub(amount)}"),
                )
            }
        }
        return AssessmentPolicyDecision.Reject(
            RejectReason.R_LOAD,
            listOf(
                RejectReason.R_LOAD.clientText + ". Уменьшите сумму или сначала погасите часть текущих кредитов",
            ),
        )
    }

    /** D: at most a share of the request and of the revenue, if the load allows. */
    private fun weak(
        terms: AssessmentTerms,
        product: AssessmentProduct,
        assessment: Assessment,
        rules: RuleSet,
        rate: BigDecimal,
        budget: Double,
    ): AssessmentPolicyDecision {
        val cap = minOf(terms.amount * rules.weakAmountShare, assessment.annualRevenue * rules.weakRevenueShare).toLong().roundDown()
        for (term in listOf(terms.termMonths, product.maxTermMonths).distinct()) {
            val amount = minOf(cap, maxAmount(product, term, rate, budget) ?: 0L).coerceAtMost(product.maxAmount)
            if (amount >= product.minAmount) {
                return AssessmentPolicyDecision.Counter(
                    offerTerms(product, amount, term, rate),
                    listOf("при текущем рейтинге банк готов выдать до ${rub(amount)}") +
                        if (term != terms.termMonths) listOf("на срок $term мес.") else emptyList(),
                )
            }
        }
        return rejectByScore(assessment)
    }

    private fun rejectByScore(assessment: Assessment) =
        AssessmentPolicyDecision.Reject(
            RejectReason.R_SCORE,
            listOf(RejectReason.R_SCORE.clientText + ". Что влияет сильнее всего:") +
                assessment.weakest(3).map { "${it.title}: ${it.value}" + (it.hint?.let { hint -> " — $hint" } ?: "") },
        )

    private fun fits(product: AssessmentProduct, amount: Long, term: Int, rate: BigDecimal, budget: Double): Boolean =
        budget > 0 && payments.calculate(amount, rate, term, product.revolving).monthlyPayment.toDouble() <= budget

    /** The largest amount, in steps of 10 000 ₽, whose payment fits the budget; payments grow linearly with the amount. */
    private fun maxAmount(product: AssessmentProduct, term: Int, rate: BigDecimal, budget: Double): Long? {
        if (budget <= 0) return null
        val perRuble = payments.calculate(1_000_000, rate, term, product.revolving).monthlyPayment.toDouble() / 1_000_000
        return floor(budget / perRuble).toLong().coerceAtMost(product.maxAmount).roundDown()
    }

    private fun Long.roundDown(): Long = this / roundTo * roundTo

    private fun offerTerms(product: AssessmentProduct, amount: Long, termMonths: Int, rate: BigDecimal): AssessmentOfferTerms {
        val calculation = payments.calculate(amount, rate, termMonths, product.revolving)
        return AssessmentOfferTerms(product.code, amount, termMonths, rate, calculation.monthlyPayment, calculation.totalPayment)
    }

    private fun rub(amount: Long) = assessmentRubles(amount)
}
