package ru.creditcabinet.financing.scenario.application

import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.financing.scenario.domain.FinancingCalculation
import ru.creditcabinet.financing.scenario.domain.ScenarioInputs
import ru.creditcabinet.product.domain.Product

internal data class FinancingPreviewForm(val amount: Long, val baseTermMonths: Int, val inputs: ScenarioInputs)

internal data class FinancingPreview(
    val application: LoanApplication,
    val product: Product,
    val savedScenarioRevision: Long?,
    val inputs: ScenarioInputs,
    val calculation: FinancingCalculation,
)
