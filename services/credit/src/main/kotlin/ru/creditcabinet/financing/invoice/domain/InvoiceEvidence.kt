package ru.creditcabinet.financing.invoice.domain

import java.time.DateTimeException
import java.time.LocalDate

data class InvoiceFact<T>(val value: T, val page: Int, val quote: String)

internal enum class InvoiceDateLabel {
    PAYMENT,
    VALIDITY,
}

internal data class InvoiceDateEvidence(val fact: InvoiceFact<LocalDate>, val label: InvoiceDateLabel)

internal data class InvoiceEvidence(
    val amounts: List<InvoiceFact<Long>>,
    val dates: List<InvoiceDateEvidence>,
) {
    companion object {
        private val amountLabel = Regex(
            """^(?:итого(?: к оплате)?|всего(?: к оплате)?|сумма к оплате|к оплате|amount due|total(?: amount due)?)\s*[:—-]?\s*""",
            RegexOption.IGNORE_CASE,
        )
        private val amountValue = Regex(
            """(?:[1-9]\d{0,2}(?: \d{3})+|[1-9]\d{0,8})(?:[.,]00)?\s*(?:₽|руб(?:лей|ля)?\.?|RUB)?""",
            RegexOption.IGNORE_CASE,
        )
        private val paymentDateLabel = Regex(
            """^(?:ожидаемая дата (?:оплаты|поступления)|дата (?:оплаты|платежа|поступления)|срок оплаты|оплатить до|due date|payment date)\s*[:—-]?\s*""",
            RegexOption.IGNORE_CASE,
        )
        private val validityDateLabel = Regex(
            """^(?:сч[её]т действителен до|действителен до|срок действия(?: сч[её]та)?|valid until)\s*[:—-]?\s*""",
            RegexOption.IGNORE_CASE,
        )
        private val russianDate = Regex("""(\d{2})[./](\d{2})[./](\d{4})(?:\s*г\.?)?""")
        private val isoDate = Regex("""\d{4}-\d{2}-\d{2}""")
        private val dateWithMonth = Regex(
            """(\d{1,2})\s+(января|февраля|марта|апреля|мая|июня|июля|августа|сентября|октября|ноября|декабря)\s+(\d{4})(?:\s*г\.?)?""",
            RegexOption.IGNORE_CASE,
        )
        private val months = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
        private val space = Regex("""[\p{Zs}\t]+""")

        fun from(lines: List<InvoicePdfLine>): InvoiceEvidence {
            // Quotes always come from PDFBox. Normalization is used for matching only.
            val normalized = lines.map { it to it.text.replace(space, " ") }
            val amounts = normalized.mapNotNull { (line, text) ->
                amountLabel.find(text)?.let { label -> parseAmount(text.substring(label.range.last + 1)) }
                    ?.let { InvoiceFact(it, line.page, line.text) }
            }.distinctBy { it.value }
            val dates = normalized.mapNotNull { (line, text) -> dateEvidence(line, text) }
                .distinctBy { it.fact.value to it.label }
            return InvoiceEvidence(amounts, dates)
        }

        private fun parseAmount(text: String): Long? {
            if (!amountValue.matches(text)) return null
            val rubles = text.takeWhile { it.isDigit() || it == ' ' }.replace(" ", "")
            return rubles.toLongOrNull()?.takeIf { it in 1..999_999_999 }
        }

        private fun dateEvidence(line: InvoicePdfLine, text: String): InvoiceDateEvidence? {
            val paymentLabel = paymentDateLabel.find(text)
            val match = paymentLabel ?: validityDateLabel.find(text) ?: return null
            val label = if (paymentLabel != null) InvoiceDateLabel.PAYMENT else InvoiceDateLabel.VALIDITY
            val date = parseDate(text.substring(match.range.last + 1)) ?: return null
            return InvoiceDateEvidence(InvoiceFact(date, line.page, line.text), label)
        }

        private fun parseDate(text: String): LocalDate? =
            try {
                val numbered = russianDate.matchEntire(text)
                val named = dateWithMonth.matchEntire(text)
                when {
                    numbered != null -> {
                        val (day, month, year) = numbered.destructured
                        LocalDate.of(year.toInt(), month.toInt(), day.toInt())
                    }
                    named != null -> {
                        val (day, month, year) = named.destructured
                        LocalDate.of(year.toInt(), months.indexOf(month.lowercase()) + 1, day.toInt())
                    }
                    isoDate.matches(text) -> LocalDate.parse(text)
                    else -> null
                }?.takeIf { it.year in 2000..2100 }
            } catch (_: DateTimeException) {
                null
            }
    }
}
