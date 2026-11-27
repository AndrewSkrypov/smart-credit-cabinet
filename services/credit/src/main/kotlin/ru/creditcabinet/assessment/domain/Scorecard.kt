package ru.creditcabinet.assessment.domain

import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.product.domain.LoanMath
import ru.creditcabinet.product.domain.Product
import java.time.LocalDate

internal val creditAssessmentPayments = AssessmentPaymentCalculator { amount, rate, termMonths, revolving ->
    val payment = LoanMath.calculate(amount, rate, termMonths, revolving)
    AssessmentPayments(payment.monthlyPayment, payment.totalPayment)
}

fun ApplicationTerms.forAssessment() = AssessmentTerms(productCode, amount, termMonths, declaredRevenue12m)

fun Product.forAssessment() = AssessmentProduct(code, name, minAmount, maxAmount, minTermMonths, maxTermMonths, baseRate, autoApprovalLimit, revolving)

object Scorecard {
    private val scoring = AssessmentScorecard(creditAssessmentPayments)

    fun assess(terms: ApplicationTerms, product: Product, data: SourceData, rules: RuleSet, today: LocalDate): Assessment =
        scoring.assess(terms.forAssessment(), product.forAssessment(), data, rules, today)

    fun revenue(data: SourceData, terms: ApplicationTerms): Pair<Long, RevenueSource> = scoring.revenue(data, terms.forAssessment())

    fun coefficientOfVariation(values: List<Long>): Double? = scoring.coefficientOfVariation(values)
}
