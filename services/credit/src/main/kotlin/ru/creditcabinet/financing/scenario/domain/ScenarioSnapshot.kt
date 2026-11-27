package ru.creditcabinet.financing.scenario.domain

import java.math.BigDecimal

data class ScenarioSnapshot(
    val revision: Long,
    val status: FinancingScenarioStatus,
    val inputs: ScenarioInputs,
    val loanAmount: Long,
    val annualRatePercent: BigDecimal,
    val options: List<FinancingScenarioOption>,
    val changes: List<String> = emptyList(),
)
