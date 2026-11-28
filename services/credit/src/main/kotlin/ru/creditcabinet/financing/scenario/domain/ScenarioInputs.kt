package ru.creditcabinet.financing.scenario.domain

import java.time.LocalDate

data class ScenarioInputs(
    val openingBalance: Long,
    val expectedReceiptAmount: Long,
    val expectedReceiptDate: LocalDate,
    val paymentDate: LocalDate,
    val delayDays: Int,
    val alternativeTermMonths: Int,
    val selectedTermMonths: Int,
    val source: FinancingScenarioSource? = null,
)
