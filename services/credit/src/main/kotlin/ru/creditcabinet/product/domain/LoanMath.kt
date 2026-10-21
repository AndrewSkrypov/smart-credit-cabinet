package ru.creditcabinet.product.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

data class ScheduleRow(val month: Int, val payment: BigDecimal, val principal: BigDecimal, val interest: BigDecimal, val balance: BigDecimal)

data class Calculation(
    val rate: BigDecimal,
    /** Annuity payment, or for a revolving limit the first minimal payment at full use. */
    val monthlyPayment: BigDecimal,
    val totalPayment: BigDecimal,
    val overpayment: BigDecimal,
    val schedule: List<ScheduleRow>,
)

/**
 * Payments in rubles with kopecks. A loan is repaid by an annuity; a revolving limit (overdraft,
 * card) by a minimal payment of the month's interest plus [REVOLVING_PRINCIPAL_SHARE] of the debt,
 * shown for the case the whole limit is used.
 */
object LoanMath {
    private val MC = MathContext.DECIMAL64
    private val HUNDRED = BigDecimal(100)
    private val TWELVE = BigDecimal(12)
    val REVOLVING_PRINCIPAL_SHARE = BigDecimal("0.10")

    fun monthlyRate(annualRatePercent: BigDecimal): BigDecimal = annualRatePercent.divide(HUNDRED, MC).divide(TWELVE, MC)

    /** Annuity payment: P * r / (1 - (1 + r)^-n). */
    fun annuityPayment(amount: Long, annualRatePercent: BigDecimal, termMonths: Int): BigDecimal {
        require(amount > 0 && termMonths > 0) { "Сумма и срок должны быть больше нуля" }
        val r = monthlyRate(annualRatePercent)
        val principal = BigDecimal(amount)
        if (r.signum() == 0) return principal.divide(BigDecimal(termMonths), 2, RoundingMode.HALF_UP)
        val factor = BigDecimal.ONE.add(r).pow(termMonths, MC)
        return principal.multiply(r).multiply(factor).divide(factor.subtract(BigDecimal.ONE), 2, RoundingMode.HALF_UP)
    }

    fun calculate(amount: Long, annualRatePercent: BigDecimal, termMonths: Int, revolving: Boolean): Calculation =
        if (revolving) revolving(amount, annualRatePercent, termMonths) else annuity(amount, annualRatePercent, termMonths)

    private fun annuity(amount: Long, rate: BigDecimal, termMonths: Int): Calculation {
        val r = monthlyRate(rate)
        val payment = annuityPayment(amount, rate, termMonths)
        var balance = BigDecimal(amount).setScale(2)
        val rows = (1..termMonths).map { month ->
            val interest = balance.multiply(r).setScale(2, RoundingMode.HALF_UP)
            // The last payment closes the debt exactly, absorbing the rounding of earlier months.
            val principal = if (month == termMonths) balance else payment.subtract(interest)
            balance = balance.subtract(principal)
            ScheduleRow(month, principal.add(interest), principal, interest, balance)
        }
        val total = rows.fold(BigDecimal.ZERO) { sum, row -> sum.add(row.payment) }
        return Calculation(rate, payment, total, total.subtract(BigDecimal(amount)), rows)
    }

    private fun revolving(amount: Long, rate: BigDecimal, termMonths: Int): Calculation {
        val r = monthlyRate(rate)
        var balance = BigDecimal(amount).setScale(2)
        val rows = (1..termMonths).map { month ->
            val interest = balance.multiply(r).setScale(2, RoundingMode.HALF_UP)
            // The limit ends with the term: whatever is left is repaid in the last month.
            val principal = if (month == termMonths) balance else balance.multiply(REVOLVING_PRINCIPAL_SHARE).setScale(2, RoundingMode.HALF_UP)
            balance = balance.subtract(principal)
            ScheduleRow(month, principal.add(interest), principal, interest, balance)
        }
        val total = rows.fold(BigDecimal.ZERO) { sum, row -> sum.add(row.payment) }
        return Calculation(rate, rows.first().payment, total, total.subtract(BigDecimal(amount)), rows)
    }
}
