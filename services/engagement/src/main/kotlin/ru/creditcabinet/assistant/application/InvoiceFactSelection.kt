package ru.creditcabinet.assistant.application

data class InvoiceFactCandidates(val amounts: List<String>, val dates: List<String>)

data class InvoiceFactChoice(val amountIndex: Int? = null, val dateIndex: Int? = null)

fun interface InvoiceFactsModel {
    fun choose(candidates: InvoiceFactCandidates): InvoiceFactChoice
}

class InvoiceFactSelection(private val model: InvoiceFactsModel) {
    fun choose(candidates: InvoiceFactCandidates): InvoiceFactChoice {
        require(candidates.amounts.size in 1..MAX_CANDIDATES && candidates.dates.size in 1..MAX_CANDIDATES) { "Некорректные варианты фактов" }
        require((candidates.amounts + candidates.dates).all { it.isNotBlank() && it.length <= MAX_LINE_LENGTH }) {
            "Строка счёта превышает допустимый размер или пуста"
        }
        val choice = model.choose(candidates)
        return InvoiceFactChoice(
            choice.amountIndex?.takeIf { it in candidates.amounts.indices },
            choice.dateIndex?.takeIf { it in candidates.dates.indices },
        )
    }

    private companion object {
        const val MAX_CANDIDATES = 2
        const val MAX_LINE_LENGTH = 180
    }
}
