package ru.creditcabinet.financing.scenario

import com.fasterxml.jackson.annotation.JsonInclude
import java.math.BigDecimal

data class FinancingScenarioSnapshot(
    val revision: Long,
    val status: FinancingScenarioStatus,
    val inputs: FinancingScenarioInputs,
    val loanAmount: Long,
    val annualRatePercent: BigDecimal,
    val options: List<FinancingScenarioOption>,
    @JsonInclude(JsonInclude.Include.NON_EMPTY) val changes: List<String> = emptyList(),
)
