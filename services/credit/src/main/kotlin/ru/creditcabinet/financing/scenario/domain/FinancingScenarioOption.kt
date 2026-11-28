package ru.creditcabinet.financing.scenario.domain

import java.math.BigDecimal

data class FinancingScenarioOption(
    val termMonths: Int,
    val monthlyPayment: BigDecimal,
    val totalInterest: BigDecimal,
    val balanceOnPaymentDate: BigDecimal,
)
