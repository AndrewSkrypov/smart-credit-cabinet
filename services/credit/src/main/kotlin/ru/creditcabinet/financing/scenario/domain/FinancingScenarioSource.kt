package ru.creditcabinet.financing.scenario.domain

import java.util.UUID

data class FinancingScenarioSource(
    val page: Int,
    val amountQuote: String,
    val dateQuote: String,
    val documentId: UUID? = null,
)
