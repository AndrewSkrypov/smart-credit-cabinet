package ru.creditcabinet.assistant.application

import ru.creditcabinet.assistant.domain.AnswerLink
import ru.creditcabinet.assistant.domain.FinancingNeed
import java.math.BigDecimal
import java.util.Locale

/** The answer to «what should I take»: real products and payments, or one question back. */
object ProductAdvice {
    const val ASK_PURPOSE = "На что нужны деньги?"
    const val ASK_AMOUNT = "Сколько нужно и на какой срок?"
    private const val DEFAULT_TERM = 12

    fun answer(need: FinancingNeed, products: AssistantProducts, authorization: String, excludeProductCode: String? = null): Pair<String, AnswerLink?> {
        val purposes = products.purposes(authorization)
        if (need.purpose == null) {
            val examples = purposes.values.take(4).joinToString(", ") { it.lowercase() }
            return "$ASK_PURPOSE Например: $examples. Подберу продукт и посчитаю платёж." to null
        }
        val purposeTitle = purposes[need.purpose] ?: "эту цель"
        if (need.amount == null) {
            val fitting = products.catalogue(authorization)?.filter { product -> need.purpose in product.purposes }.orEmpty()
            val list = fitting.joinToString("\n") {
                "— ${it.name}: до ${rub(it.maxAmount)}, ставка от ${percent(it.baseRate)}"
            }
            val intro = if (fitting.isEmpty()) "" else "Для цели «$purposeTitle» есть:\n$list\n\n"
            return "$intro$ASK_AMOUNT Посчитаю платёж по каждому продукту." to link(need.purpose, null, need.termMonths)
        }
        val term = need.termMonths ?: DEFAULT_TERM
        val matches = products.match(need.purpose, need.amount, term, authorization)
            ?: return "Подбор сейчас недоступен. Попробуйте ещё раз через несколько минут." to
                (if (excludeProductCode == null) link(need.purpose, need.amount, term) else null)
        val eligible = matches.filter { it.product.code != excludeProductCode }
        val fitting = eligible.filter { it.fits }
        val header = "Цель «$purposeTitle», ${rub(need.amount)} на ${months(term)}" + (if (need.termMonths == null) " (срок я взял для примера)" else "") + "."
        val body = if (fitting.isEmpty()) {
            val nearest = eligible.firstOrNull()
            (if (excludeProductCode == null) "Ни один продукт не подходит на этих условиях." else "Других продуктов с такими условиями сейчас нет.") +
                (nearest?.let { "\nБлиже всего — ${it.product.name}: ${it.adjustments.joinToString("; ")}." } ?: "")
        } else {
            "Подходят:\n" + fitting.joinToString("\n") { match ->
                val payment =
                    match.monthlyPayment?.let { "платёж ${rubExact(it)} в месяц" } ?: "платёж — по использованию лимита"
                "— ${match.product.name}: ставка от ${percent(match.product.baseRate)}, $payment"
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
