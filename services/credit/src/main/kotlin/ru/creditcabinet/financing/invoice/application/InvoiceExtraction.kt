package ru.creditcabinet.financing.invoice.application

import org.springframework.stereotype.Service
import ru.creditcabinet.financing.invoice.domain.InvoiceDateLabel
import ru.creditcabinet.financing.invoice.domain.InvoiceEvidence
import ru.creditcabinet.financing.invoice.domain.InvoiceFact
import java.time.LocalDate

enum class InvoiceExtractionMode { MODEL, MANUAL }

data class InvoiceExtractionResult(
    val mode: InvoiceExtractionMode,
    val amount: InvoiceFact<Long>? = null,
    val dates: List<InvoiceFact<LocalDate>> = emptyList(),
    val reason: String? = null,
    val needsConfirmation: Boolean = true,
)

internal data class InvoiceModelChoice(val amountIndex: Int?, val dateIndex: Int?)

internal fun interface InvoiceModel {
    fun select(amountQuotes: List<String>, dateQuotes: List<String>, authorization: String): InvoiceModelChoice?
}

@Service
internal class InvoiceExtraction(private val model: InvoiceModel, private val reader: InvoiceTextReader) {

    fun extract(bytes: ByteArray, authorization: String): InvoiceExtractionResult {
        val evidence = InvoiceEvidence.from(reader.read(bytes))
        val amount = evidence.amounts.singleOrNull()
        val dates = evidence.dates.map { it.fact }.distinctBy { it.value }
        fun manual(reason: String, candidates: List<InvoiceFact<LocalDate>> = dates.takeIf { it.size <= 2 }.orEmpty()) =
            InvoiceExtractionResult(InvoiceExtractionMode.MANUAL, amount, candidates, reason)

        if (amount == null || dates.isEmpty()) return manual("Проверьте сумму и дату по счёту и заполните пропуски")
        if (dates.size > 1) return manual("В счёте несколько дат. Уточните дату ожидаемого поступления")
        if (evidence.dates.none { it.label == InvoiceDateLabel.PAYMENT }) {
            return manual("В счёте указана дата действия. Уточните дату ожидаемого поступления")
        }
        val date = dates.single()
        val choice = model.select(listOf(amount.quote), listOf(date.quote), authorization)
        // The model selects indexes. Neither its prose nor numbers enter the response.
        if (choice?.amountIndex != 0 || choice.dateIndex != 0) {
            return manual("Подтвердите сумму и дату по исходным строкам счёта")
        }
        return InvoiceExtractionResult(InvoiceExtractionMode.MODEL, amount, dates)
    }
}
