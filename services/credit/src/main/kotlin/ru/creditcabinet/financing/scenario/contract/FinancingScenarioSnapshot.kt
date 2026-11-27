package ru.creditcabinet.financing.scenario.contract

import com.fasterxml.jackson.annotation.JsonInclude
import ru.creditcabinet.financing.scenario.domain.FinancingScenarioOption
import ru.creditcabinet.financing.scenario.domain.FinancingScenarioStatus
import ru.creditcabinet.financing.scenario.domain.ScenarioSnapshot
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

internal fun FinancingScenarioSnapshot.toDomain() = ScenarioSnapshot(
    revision,
    status,
    inputs.toDomain(),
    loanAmount,
    annualRatePercent,
    options,
    changes,
)

internal fun ScenarioSnapshot.toContract() = FinancingScenarioSnapshot(
    revision,
    status,
    inputs.toContract(),
    loanAmount,
    annualRatePercent,
    options,
    changes,
)
