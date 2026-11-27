package ru.creditcabinet.assessment.domain

import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.preapproved.domain.PreApprovedOffer
import ru.creditcabinet.product.domain.LoanMath
import ru.creditcabinet.product.domain.Product
import java.math.BigDecimal

/** Terms the bank is ready to lend on. */
data class OfferTerms(
    val productCode: String,
    val amount: Long,
    val termMonths: Int,
    val rate: BigDecimal,
    val monthlyPayment: BigDecimal,
    val totalPayment: BigDecimal,
) {
    companion object {
        fun of(product: Product, amount: Long, termMonths: Int, rate: BigDecimal): OfferTerms {
            val calculation = LoanMath.calculate(amount, rate, termMonths, product.revolving)
            return OfferTerms(product.code, amount, termMonths, rate, calculation.monthlyPayment, calculation.totalPayment)
        }
    }
}

sealed interface PolicyDecision {
    /** The requested terms, at the rate for the rating. */
    data class Approve(val terms: OfferTerms) : PolicyDecision

    /** Other terms the bank can lend on: a longer term, a smaller amount or another product. */
    data class Counter(val terms: OfferTerms, val changes: List<String>) : PolicyDecision

    data class Reject(val reason: RejectReason, val explanation: List<String>) : PolicyDecision

    /** A person decides; [recommendation] is what the rules would do without the reasons. */
    data class Manual(val reasons: List<ManualReason>, val recommendation: PolicyDecision?) : PolicyDecision
}

object CreditPolicy {
    private val policy = AssessmentPolicy(creditAssessmentPayments)

    fun decide(
        terms: ApplicationTerms,
        product: Product,
        products: List<Product>,
        assessment: Assessment,
        rules: RuleSet,
        preApprovedOffer: PreApprovedOffer? = null,
    ): PolicyDecision = policy.decide(
        terms.forAssessment(),
        product.forAssessment(),
        products.map(Product::forAssessment),
        assessment,
        rules,
        preApprovedOffer?.let { AssessmentPreApproval(it.productCode, it.maxAmount, it.minTermMonths, it.maxTermMonths, it.rate) },
    ).forCredit()

    fun rate(product: Product, rating: Rating?, rules: RuleSet): BigDecimal = policy.rate(product.forAssessment(), rating, rules)
}

fun AssessmentOfferTerms.forCredit() = OfferTerms(productCode, amount, termMonths, rate, monthlyPayment, totalPayment)

fun AssessmentPolicyDecision.forCredit(): PolicyDecision = when (this) {
    is AssessmentPolicyDecision.Approve -> PolicyDecision.Approve(terms.forCredit())
    is AssessmentPolicyDecision.Counter -> PolicyDecision.Counter(terms.forCredit(), changes)
    is AssessmentPolicyDecision.Reject -> PolicyDecision.Reject(reason, explanation)
    is AssessmentPolicyDecision.Manual -> PolicyDecision.Manual(reasons, recommendation?.forCredit())
}
