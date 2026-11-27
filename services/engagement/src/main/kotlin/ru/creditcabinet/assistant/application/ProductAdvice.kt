package ru.creditcabinet.assistant.application

import ru.creditcabinet.assistant.domain.AnswerLink
import ru.creditcabinet.assistant.domain.FinancingNeed
import ru.creditcabinet.assistant.infrastructure.CreditProducts
import java.math.BigDecimal
import java.util.Locale

/** The answer to «what should I take»: real products and payments, or one question back. */
object ProductAdvice {
    const val ASK_PURPOSE = "На что нужны деньги?"
    const val ASK_AMOUNT = "Сколько нужно и на какой срок?"
    private const val DEFAULT_TERM = 12

    fun answer(need: FinancingNeed, products: CreditProducts, authorization: String, excludeProductCode: String? = null): Pair<String, AnswerLink?> {
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
            ?: return "Подбор сейчас недоступен. Попробуйте ещё раз через несколько минут." to
                (if (excludeProductCode == null) link(need.purpose, need.amount, term) else null)
        val eligible = matches.filter { it.path("product").path("code").asText() != excludeProductCode }
        val fitting = eligible.filter { it["fits"].asBoolean() }
        val header = "Цель «$purposeTitle», ${rub(need.amount)} на ${months(term)}" + (if (need.termMonths == null) " (срок я взял для примера)" else "") + "."
        val body = if (fitting.isEmpty()) {
            val nearest = eligible.firstOrNull()
            (if (excludeProductCode == null) "Ни один продукт не подходит на этих условиях." else "Других продуктов с такими условиями сейчас нет.") +
                (nearest?.let { "\nБлиже всего — ${it["product"]["name"].asText()}: ${it["adjustments"].joinToString("; ") { a -> a.asText() }}." } ?: "")
        } else {
            "Подходят:\n" + fitting.joinToString("\n") { match ->
                val payment =
                    match["monthlyPayment"].takeIf { !it.isNull }?.let { "платёж ${rubExact(it.decimalValue())} в месяц" } ?: "платёж — по использованию лимита"
                "— ${match["product"]["name"].asText()}: ставка от ${percent(match["product"]["baseRate"].decimalValue())}, $payment"
            }
        }
        val tail = "\n\nЭто расчёт по базовой ставке. Итоговые условия банк предложит после оценки заявки — одобрение заранее не обещаю."
        val action = if (excludeProductCode == null || fitting.isNotEmpty()) link(need.purpose, need.amount, term, excludeProductCode) else null
        return "$header\n$body$tail" to action
    }

    private fun link(purpose: String, amount: Long?, term: Int?, excludeProductCode: String? = null): AnswerLink =
        AnswerLink(
            "Оформить",
            "/client/new?" + listOfNotNull(
                "purpose=$purpose",
                amount?.let { "amount=$it" },
                term?.let { "termMonths=$it" },
                excludeProductCode?.let { "excludeProductCode=$it" },
            ).joinToString("&"),
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
