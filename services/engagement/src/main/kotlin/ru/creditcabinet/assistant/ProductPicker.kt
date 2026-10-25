package ru.creditcabinet.assistant

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.Duration
import java.util.Locale

/** What a client asked to finance, as far as the question says: any part may be missing. */
data class FinancingNeed(val purpose: String?, val amount: Long?, val termMonths: Int?)

/** A button under an answer: where the cabinet continues what the assistant started. */
data class AnswerLink(val label: String, val path: String)

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

/** The credit service's product matcher, called with the person's own token. */
@Component
class CreditProducts(
    @Value("\${cabinet.credit.url:}") creditUrl: String,
    builder: RestClient.Builder,
) {
    private val client: RestClient? = creditUrl.takeIf { it.isNotBlank() }?.let {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(2))
            setReadTimeout(Duration.ofSeconds(5))
        }
        builder.clone().baseUrl(it).requestFactory(requestFactory).build()
    }

    fun purposes(authorization: String): Map<String, String> =
        runCatching {
            client?.get()?.uri("/api/v1/products/purposes")?.header(HttpHeaders.AUTHORIZATION, authorization)?.retrieve()?.body(JsonNode::class.java)
                ?.associate { it["code"].asText() to it["title"].asText() }
        }.getOrNull().orEmpty()

    fun match(purpose: String, amount: Long, termMonths: Int, authorization: String): JsonNode? =
        runCatching {
            client?.post()?.uri("/api/v1/products/match")?.header(HttpHeaders.AUTHORIZATION, authorization)
                ?.contentType(MediaType.APPLICATION_JSON)
                ?.body(mapOf("purpose" to purpose, "amount" to amount, "termMonths" to termMonths))
                ?.retrieve()?.body(JsonNode::class.java)
        }.getOrNull()

    fun catalogue(authorization: String): JsonNode? =
        runCatching { client?.get()?.uri("/api/v1/products")?.header(HttpHeaders.AUTHORIZATION, authorization)?.retrieve()?.body(JsonNode::class.java) }
            .getOrNull()
}

/** The answer to «what should I take»: real products and payments, or one question back. */
object ProductAdvice {
    const val ASK_PURPOSE = "На что нужны деньги?"
    const val ASK_AMOUNT = "Сколько нужно и на какой срок?"
    private const val DEFAULT_TERM = 12

    fun answer(need: FinancingNeed, products: CreditProducts, authorization: String): Pair<String, AnswerLink?> {
        val purposes = products.purposes(authorization)
        if (need.purpose == null) {
            val examples = purposes.values.take(4).joinToString(", ") { it.lowercase() }
            return "$ASK_PURPOSE Например: $examples. Подберу продукт и посчитаю платёж." to null
        }
        val purposeTitle = purposes[need.purpose] ?: "эту цель"
        if (need.amount == null) {
            val fitting = products.catalogue(authorization)?.filter { product -> product["purposes"].any { it.asText() == need.purpose } }.orEmpty()
            val list = fitting.joinToString("\n") {
                "— ${it["name"].asText()}: до ${rub(it["maxAmount"].asLong())}, ставка от ${percent(it["baseRate"].decimalValue())}"
            }
            val intro = if (fitting.isEmpty()) "" else "Для цели «$purposeTitle» есть:\n$list\n\n"
            return "$intro$ASK_AMOUNT Посчитаю платёж по каждому продукту." to link(need.purpose, null, need.termMonths)
        }
        val term = need.termMonths ?: DEFAULT_TERM
        val matches = products.match(need.purpose, need.amount, term, authorization)
            ?: return "Подбор сейчас недоступен — откройте «Подобрать финансирование», там тот же расчёт." to link(need.purpose, need.amount, term)
        val fitting = matches.filter { it["fits"].asBoolean() }
        val header = "Цель «$purposeTitle», ${rub(need.amount)} на ${months(term)}" + (if (need.termMonths == null) " (срок я взял для примера)" else "") + "."
        val body = if (fitting.isEmpty()) {
            val nearest = matches.firstOrNull()
            "Ни один продукт не подходит на этих условиях." +
                (nearest?.let { "\nБлиже всего — ${it["product"]["name"].asText()}: ${it["adjustments"].joinToString("; ") { a -> a.asText() }}." } ?: "")
        } else {
            "Подходят:\n" + fitting.joinToString("\n") { match ->
                val payment =
                    match["monthlyPayment"].takeIf { !it.isNull }?.let { "платёж ${rubExact(it.decimalValue())} в месяц" } ?: "платёж — по использованию лимита"
                "— ${match["product"]["name"].asText()}: ставка от ${percent(match["product"]["baseRate"].decimalValue())}, $payment"
            }
        }
        val tail = "\n\nЭто расчёт по базовой ставке. Итоговые условия банк предложит после оценки заявки — одобрение заранее не обещаю."
        return "$header\n$body$tail" to link(need.purpose, need.amount, term)
    }

    private fun link(purpose: String, amount: Long?, term: Int?): AnswerLink =
        AnswerLink(
            "Оформить",
            "/client/new?" + listOfNotNull("purpose=$purpose", amount?.let { "amount=$it" }, term?.let { "term=$it" }).joinToString("&"),
        )

    private fun rub(amount: Long) = "%,d ₽".format(Locale.ROOT, amount).replace(',', ' ')

    private fun rubExact(amount: BigDecimal) = "%,.2f ₽".format(Locale.ROOT, amount).replace(',', ' ').replace('.', ',')

    private fun percent(rate: BigDecimal) = rate.stripTrailingZeros().toPlainString().replace('.', ',') + "%"

    private fun months(count: Int): String {
        val word = when {
            count % 100 in 11..14 -> "месяцев"
            count % 10 == 1 -> "месяц"
            count % 10 in 2..4 -> "месяца"
            else -> "месяцев"
        }
        return "$count $word"
    }
}
