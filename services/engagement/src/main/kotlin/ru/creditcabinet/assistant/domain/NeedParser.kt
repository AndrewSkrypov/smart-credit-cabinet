package ru.creditcabinet.assistant.domain

import java.math.BigDecimal

/** What a client asked to finance, as far as the question says: any part may be missing. */
data class FinancingNeed(val purpose: String?, val amount: Long?, val termMonths: Int?)

/**
 * «Подбери кредит на 2 млн на закупку на год»: the need is read by rules, the products and payments
 * come from the same matcher as the needs wizard. No figure in the answer is the model's.
 */
object NeedParser {
    private val ASKS = listOf(
        "подбер", "подобр", "посовет", "порекоменд", "какой кредит", "какой продукт", "какой вариант", "что выбрать", "что взять",
        "что подойд", "нужен кредит", "нужны деньги", "нужно денег", "хочу взять", "хочу кредит", "взять кредит",
    )

    /** Purpose codes of the credit service and the words that point to each. */
    private val PURPOSES = linkedMapOf(
        "CASH_GAP" to listOf("кассов", "разрыв", "пока клиенты", "отсрочк"),
        "CURRENT_EXPENSES" to listOf("зарплат", "аренд", "налог", "текущ"),
        "WORKING_CAPITAL" to listOf("закуп", "товар", "сырь", "оборот", "сезон"),
        "EQUIPMENT" to listOf("оборудован", "станок", "станк", "техник"),
        "VEHICLE" to listOf("транспорт", "машин", "автомоб", "грузов", "фур"),
        "RENOVATION" to listOf("ремонт", "помещен"),
        "BUSINESS_DEVELOPMENT" to listOf("развит", "новое направлен", "новую точк", "открыть", "расшир"),
    )

    private val AMOUNT = Regex("(\\d+(?:[.,]\\d+)?)\\s*(млн|миллион\\S*|млрд|тыс\\S*|т\\.\\s?р|₽|руб\\S*|р\\.)?", RegexOption.IGNORE_CASE)
    private val THOUSANDS = Regex("(\\d)[\\s\\u00A0](\\d{3})(?!\\d)")
    private val TERM_MONTHS = Regex("(\\d+)\\s*(?:мес|месяц\\S*)", RegexOption.IGNORE_CASE)
    private val TERM_YEARS = Regex("(\\d+)\\s*(?:год\\S*|лет)", RegexOption.IGNORE_CASE)

    /** «Что лучше: овердрафт или кредит?» compares products: that one the model explains from the reference. */
    fun asksForProduct(text: String): Boolean = text.lowercase().let { lower -> ASKS.any { it in lower } && " или " !in lower }

    fun parse(text: String): FinancingNeed {
        var lower = text.lowercase().replace('ё', 'е')
        // «2 000 000» is one number.
        while (THOUSANDS.containsMatchIn(lower)) lower = lower.replace(THOUSANDS, "$1$2")
        val purpose = PURPOSES.entries.firstOrNull { (_, words) -> words.any { it in lower } }?.key
        return FinancingNeed(purpose, amount(lower), term(lower))
    }

    private fun term(text: String): Int? {
        TERM_MONTHS.find(text)?.let { return it.groupValues[1].toIntOrNull() }
        TERM_YEARS.find(text)?.let { return it.groupValues[1].toIntOrNull()?.times(12) }
        return when {
            "полгода" in text || "пол года" in text -> 6
            "полтора года" in text -> 18
            "год" in text.split(Regex("[^а-я0-9]+")) -> 12
            else -> null
        }
    }

    private fun amount(text: String): Long? {
        // Words for amounts that people write without digits.
        if ("полтора миллиона" in text || "полтора млн" in text) return 1_500_000
        if ("миллион" in text && AMOUNT.findAll(text).none { it.groupValues[2].isNotEmpty() }) return 1_000_000
        return AMOUNT.findAll(text).mapNotNull { match ->
            val number = match.groupValues[1].replace(',', '.').toBigDecimalOrNull() ?: return@mapNotNull null
            val unit = match.groupValues[2].lowercase()
            val multiplier = when {
                unit.startsWith("млрд") -> 1_000_000_000L
                unit.startsWith("млн") || unit.startsWith("миллион") -> 1_000_000L
                unit.startsWith("тыс") || unit.startsWith("т.") || unit == "к" -> 1_000L
                else -> 1L
            }
            // A bare number counts only when it looks like money, not like a term or a count.
            if (multiplier == 1L && unit.isEmpty() && number < BigDecimal(10_000)) return@mapNotNull null
            number.multiply(BigDecimal(multiplier)).toLong().takeIf { it > 0 }
        }.firstOrNull()
    }
}
