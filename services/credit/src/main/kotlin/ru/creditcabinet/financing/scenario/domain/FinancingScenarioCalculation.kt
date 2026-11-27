package ru.creditcabinet.financing.scenario.domain

import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.financing.scenario.domain.FinancingScenarioOption
import ru.creditcabinet.financing.scenario.domain.FinancingScenarioStatus
import ru.creditcabinet.product.domain.LoanMath
import ru.creditcabinet.product.domain.Product
import java.math.BigDecimal
import java.time.temporal.ChronoUnit

internal object FinancingScenarioCalculation {
    fun draft(revision: Long, inputs: ScenarioInputs, terms: ApplicationTerms, product: Product): ScenarioSnapshot {
        val comparisonTerms = listOf(terms.termMonths, inputs.alternativeTermMonths)
        require(product.amountFits(terms.amount)) { "Сумма черновика выходит за пределы продукта" }
        require(comparisonTerms.distinct().size == 2 && comparisonTerms.all(product::termFits)) { "Укажите два разных допустимых срока" }
        require(inputs.selectedTermMonths in comparisonTerms) { "Выберите один из сравниваемых сроков" }
        require(inputs.expectedReceiptAmount >= 0 && inputs.delayDays >= 0) { "Поступление и задержка не могут быть отрицательными" }
        inputs.source?.let {
            require(it.page > 0 && it.amountQuote.isNotBlank() && it.dateQuote.isNotBlank()) { "Укажите страницу и цитаты из счёта" }
            require(it.amountQuote.length <= MAX_QUOTE_LENGTH && it.dateQuote.length <= MAX_QUOTE_LENGTH) { "Сократите цитаты из счёта" }
        }

        val daysUntilPayment = ChronoUnit.DAYS.between(inputs.expectedReceiptDate, inputs.paymentDate)
        val receiptByPayment = if (daysUntilPayment >= inputs.delayDays) inputs.expectedReceiptAmount else 0L
        val available = BigDecimal.valueOf(inputs.openingBalance).add(BigDecimal.valueOf(receiptByPayment))
        val options = comparisonTerms.map { term ->
            val calculation = LoanMath.calculate(terms.amount, product.baseRate, term, product.revolving)
            FinancingScenarioOption(term, calculation.monthlyPayment, calculation.overpayment, available.subtract(calculation.monthlyPayment))
        }
        return ScenarioSnapshot(revision, FinancingScenarioStatus.DRAFT, inputs, terms.amount, product.baseRate, options)
    }

    private const val MAX_QUOTE_LENGTH = 500
}
