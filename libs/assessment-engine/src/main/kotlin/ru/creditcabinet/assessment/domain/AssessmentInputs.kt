package ru.creditcabinet.assessment.domain

import java.math.BigDecimal
import java.util.Locale

data class AssessmentTerms(val productCode: String, val amount: Long, val termMonths: Int, val declaredRevenue12m: Long?)

data class AssessmentProduct(
    val code: String,
    val name: String,
    val minAmount: Long,
    val maxAmount: Long,
    val minTermMonths: Int,
    val maxTermMonths: Int,
    val baseRate: BigDecimal,
    val autoApprovalLimit: Long,
    val revolving: Boolean,
) {
    fun amountFits(amount: Long) = amount in minAmount..maxAmount

    fun termFits(termMonths: Int) = termMonths in minTermMonths..maxTermMonths
}

data class AssessmentPreApproval(
    val productCode: String,
    val maxAmount: Long,
    val minTermMonths: Int,
    val maxTermMonths: Int,
    val rate: BigDecimal,
) {
    fun isWithinBounds(terms: AssessmentTerms) =
        productCode == terms.productCode && terms.amount in 1..maxAmount && terms.termMonths in minTermMonths..maxTermMonths
}

data class AssessmentPayments(val monthlyPayment: BigDecimal, val totalPayment: BigDecimal)

fun interface AssessmentPaymentCalculator {
    fun calculate(amount: Long, rate: BigDecimal, termMonths: Int, revolving: Boolean): AssessmentPayments
}

internal fun assessmentRubles(amount: Long): String = String.format(Locale.ROOT, "%,d", amount).replace(',', '\u00A0') + "\u00A0₽"
