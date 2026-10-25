package ru.creditcabinet.advice.domain

import ru.creditcabinet.product.domain.LoanMath
import java.math.BigDecimal
import java.util.Locale
import kotlin.math.roundToInt

/**
 * What an advice is for the underwriter: a stop factor ends the case, a risk weighs against,
 * a check is to look at, an ask is a question or document for the client, an option is terms
 * that would pass, an info is a fact of the procedure.
 */
enum class AdviceKind { STOP, RISK, CHECK, ASK, OPTION, INFO }

data class AdviceItem(val code: String, val kind: AdviceKind, val title: String, val text: String)

/** Parameter names of the model: facts of the application and its sources, then what the rules derive. */
object P {
    const val AMOUNT = "amount"
    const val TERM = "termMonths"
    const val BASE_RATE = "baseRate"
    const val MAX_TERM = "maxTermMonths"
    const val MIN_AMOUNT = "minAmount"
    const val AUTO_LIMIT = "autoApprovalLimit"
    const val ANNUAL_REVENUE = "annualRevenue"
    const val EXISTING_PAYMENTS = "existingPayments"
    const val MAX_LOAD = "maxLoad"
    const val REVENUE_MISMATCH = "revenueMismatchAllowed"
    const val DECLARED_REVENUE = "declaredRevenue"
    const val TAX_REVENUE = "taxRevenue"
    const val STOP_FACTORS = "stopFactors"
    const val SILENT_SOURCES = "silentSources"
    const val BANK_CLIENT = "bankClient"
    const val TURNOVER_VARIATION = "turnoverVariation"
    const val INQUIRIES = "inquiries30d"
    const val WORST_OVERDUE = "worstOverdue24m"
    const val OVERDUE_NOW = "overdueNow"
    const val TAX_DEBT = "taxDebt"
    const val AGE_MONTHS = "businessAgeMonths"
    const val INDUSTRY_RISK = "industryRisk"
    const val RATING = "rating"
    const val SCORE = "score"

    const val NEW_PAYMENT = "newPayment"
    const val LOAD = "load"
    const val LOAD_EXCEEDS = "loadExceeds"
    const val PAYMENT_MAX_TERM = "paymentAtMaxTerm"
    const val LOAD_MAX_TERM = "loadAtMaxTerm"
    const val AFFORDABLE = "affordableAmount"
    const val REVENUE_GAP = "revenueGap"
    const val REVENUE_GAP_BIG = "revenueGapTooBig"
    const val TURNOVER_UNSTABLE = "turnoverUnstable"
    const val CREDIT_SEARCH = "creditSearch"
    const val PAST_DELAY = "pastDelay"
    const val YOUNG = "youngBusiness"

    const val ADVICE = "advice."
}

private fun rub(amount: Long) = "%,d ₽".format(Locale.ROOT, amount).replace(',', ' ')

private fun share(value: Double) = "${(value * 100).roundToInt()}%"

private fun Map<String, Any>.long(name: String) = (getValue(name) as Number).toLong()

private fun Map<String, Any>.int(name: String) = (getValue(name) as Number).toInt()

private fun Map<String, Any>.double(name: String) = (getValue(name) as Number).toDouble()

private fun Map<String, Any>.flag(name: String) = getValue(name) as Boolean

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.list(name: String) = getValue(name) as List<String>

private fun advice(code: String, kind: AdviceKind, title: String, text: String) = mapOf(P.ADVICE + code to AdviceItem(code, kind, title, text))

/**
 * The knowledge base of manual review, built as a MIVAR network: from the facts of the application,
 * its sources and the credit policy to what the underwriter should check, ask and may offer.
 * It never decides: the decision stays with the scorecard's recommendation and the underwriter.
 */
object UnderwritingAdviceModel {
    /** Raised when a rule changes, so a stored explanation names the knowledge it came from. */
    const val VERSION = 1

    private const val UNSTABLE_TURNOVER = 0.4
    private const val MANY_INQUIRIES = 5
    private const val YOUNG_MONTHS = 12

    val model = MivarModel(
        VERSION,
        listOf(
            // Derived facts.
            MivarRule(
                "R_PAYMENT",
                "Платёж по запрошенным условиям",
                listOf(P.AMOUNT, P.BASE_RATE, P.TERM),
                listOf(P.NEW_PAYMENT),
                explain = { "Платёж по запрошенным условиям по ставке с учётом рейтинга — ${rub(it.long(P.NEW_PAYMENT))} в месяц" },
            ) {
                mapOf(P.NEW_PAYMENT to LoanMath.annuityPayment(it.long(P.AMOUNT), it.getValue(P.BASE_RATE) as BigDecimal, it.int(P.TERM)).toLong())
            },
            MivarRule(
                "R_LOAD",
                "Долговая нагрузка с новым платежом",
                listOf(P.NEW_PAYMENT, P.EXISTING_PAYMENTS, P.ANNUAL_REVENUE),
                listOf(P.LOAD),
                explain = { "Нагрузка: платежи по кредитам с новым — ${share(it.double(P.LOAD))} месячной выручки" },
            ) {
                val monthly = it.long(P.ANNUAL_REVENUE) / 12.0
                if (monthly <= 0) null else mapOf(P.LOAD to (it.long(P.NEW_PAYMENT) + it.long(P.EXISTING_PAYMENTS)) / monthly)
            },
            MivarRule(
                "R_LOAD_LIMIT",
                "Нагрузка против порога политики",
                listOf(P.LOAD, P.MAX_LOAD),
                listOf(P.LOAD_EXCEEDS),
                explain = { "Порог политики — ${share(it.double(P.MAX_LOAD))}: " + if (it.flag(P.LOAD_EXCEEDS)) "превышен" else "соблюдён" },
            ) {
                mapOf(P.LOAD_EXCEEDS to (it.double(P.LOAD) > it.double(P.MAX_LOAD)))
            },
            MivarRule(
                "R_PAYMENT_MAX_TERM",
                "Платёж на максимальный срок продукта",
                listOf(P.AMOUNT, P.BASE_RATE, P.MAX_TERM),
                listOf(P.PAYMENT_MAX_TERM),
                explain = { "На максимальный срок ${it.int(P.MAX_TERM)} мес. платёж — ${rub(it.long(P.PAYMENT_MAX_TERM))}" },
            ) {
                mapOf(P.PAYMENT_MAX_TERM to LoanMath.annuityPayment(it.long(P.AMOUNT), it.getValue(P.BASE_RATE) as BigDecimal, it.int(P.MAX_TERM)).toLong())
            },
            MivarRule(
                "R_LOAD_MAX_TERM",
                "Нагрузка на максимальный срок",
                listOf(P.PAYMENT_MAX_TERM, P.EXISTING_PAYMENTS, P.ANNUAL_REVENUE),
                listOf(P.LOAD_MAX_TERM),
                explain = { "Нагрузка на максимальный срок — ${share(it.double(P.LOAD_MAX_TERM))}" },
            ) {
                val monthly = it.long(P.ANNUAL_REVENUE) / 12.0
                if (monthly <= 0) null else mapOf(P.LOAD_MAX_TERM to (it.long(P.PAYMENT_MAX_TERM) + it.long(P.EXISTING_PAYMENTS)) / monthly)
            },
            MivarRule(
                "R_AFFORDABLE",
                "Сумма в пределах порога нагрузки",
                listOf(P.MAX_LOAD, P.ANNUAL_REVENUE, P.EXISTING_PAYMENTS, P.BASE_RATE, P.MAX_TERM),
                listOf(P.AFFORDABLE),
                explain = { "В пределах порога на ${it.int(P.MAX_TERM)} мес. проходит до ${rub(it.long(P.AFFORDABLE))}" },
            ) {
                val budget = it.double(P.MAX_LOAD) * it.long(P.ANNUAL_REVENUE) / 12.0 - it.long(P.EXISTING_PAYMENTS)
                val perMillion = LoanMath.annuityPayment(1_000_000, it.getValue(P.BASE_RATE) as BigDecimal, it.int(P.MAX_TERM)).toDouble()
                val amount = if (budget <= 0) 0L else (budget / perMillion * 1_000_000 / 10_000).toLong() * 10_000
                mapOf(P.AFFORDABLE to amount)
            },
            MivarRule(
                "R_REVENUE_GAP",
                "Расхождение выручки с ФНС",
                listOf(P.DECLARED_REVENUE, P.TAX_REVENUE),
                listOf(P.REVENUE_GAP),
                explain = {
                    "Выручка: клиент — ${rub(
                        it.long(P.DECLARED_REVENUE),
                    )}, ФНС — ${rub(it.long(P.TAX_REVENUE))}, расхождение ${share(it.double(P.REVENUE_GAP))}"
                },
            ) {
                val tax = it.long(P.TAX_REVENUE)
                if (tax <= 0) null else mapOf(P.REVENUE_GAP to kotlin.math.abs(it.long(P.DECLARED_REVENUE) - tax).toDouble() / tax)
            },
            MivarRule(
                "R_REVENUE_GAP_BIG",
                "Расхождение против допустимого",
                listOf(P.REVENUE_GAP, P.REVENUE_MISMATCH),
                listOf(P.REVENUE_GAP_BIG),
                explain = { "Допустимое расхождение — ${share(it.double(P.REVENUE_MISMATCH))}" },
            ) {
                mapOf(P.REVENUE_GAP_BIG to (it.double(P.REVENUE_GAP) > it.double(P.REVENUE_MISMATCH)))
            },
            MivarRule(
                "R_TURNOVER",
                "Стабильность оборотов",
                listOf(P.TURNOVER_VARIATION),
                listOf(P.TURNOVER_UNSTABLE),
                explain = { "Обороты по счёту колеблются ±${share(it.double(P.TURNOVER_VARIATION))}" },
            ) {
                mapOf(P.TURNOVER_UNSTABLE to (it.double(P.TURNOVER_VARIATION) >= UNSTABLE_TURNOVER))
            },
            MivarRule(
                "R_SEARCH",
                "Активный поиск кредита",
                listOf(P.INQUIRIES),
                listOf(P.CREDIT_SEARCH),
                explain = { "Запросов кредитной истории за 30 дней — ${it.int(P.INQUIRIES)}" },
            ) {
                mapOf(P.CREDIT_SEARCH to (it.int(P.INQUIRIES) > MANY_INQUIRIES))
            },
            MivarRule(
                "R_PAST_DELAY",
                "Погашенная просрочка в прошлом",
                listOf(P.WORST_OVERDUE, P.OVERDUE_NOW),
                listOf(P.PAST_DELAY),
                explain = { "Худшая просрочка за 2 года — ${it.int(P.WORST_OVERDUE)} дн., сейчас — ${it.int(P.OVERDUE_NOW)} дн." },
            ) {
                mapOf(P.PAST_DELAY to (it.int(P.WORST_OVERDUE) > 0 && it.int(P.OVERDUE_NOW) == 0))
            },
            MivarRule(
                "R_YOUNG",
                "Короткая история бизнеса",
                listOf(P.AGE_MONTHS),
                listOf(P.YOUNG),
                explain = { "Бизнесу ${it.long(P.AGE_MONTHS)} мес." },
            ) {
                mapOf(P.YOUNG to (it.long(P.AGE_MONTHS) < YOUNG_MONTHS))
            },

            // Advice.
            MivarRule("A_STOP", "Стоп-фактор", listOf(P.STOP_FACTORS), listOf(P.ADVICE + "STOP")) {
                val factors = it.list(P.STOP_FACTORS)
                if (factors.isEmpty()) {
                    null
                } else {
                    advice(
                        "STOP",
                        AdviceKind.STOP,
                        "Стоп-фактор: ${factors.joinToString("; ")}",
                        "По кредитной политике — отказ независимо от баллов.",
                    )
                }
            },
            MivarRule("A_SOURCES", "Нет данных источника", listOf(P.SILENT_SOURCES), listOf(P.ADVICE + "SOURCES")) {
                val silent = it.list(P.SILENT_SOURCES)
                if (silent.isEmpty()) {
                    null
                } else {
                    advice(
                        "SOURCES",
                        AdviceKind.CHECK,
                        "Не ответил источник: ${silent.joinToString(", ")}",
                        "Оценка неполная. Можно запросить у клиента подтверждающий документ или повторить запрос к источнику позже.",
                    )
                }
            },
            MivarRule("A_LOAD", "Нагрузка выше порога", listOf(P.LOAD_EXCEEDS, P.LOAD, P.MAX_LOAD), listOf(P.ADVICE + "LOAD")) {
                if (!it.flag(P.LOAD_EXCEEDS)) {
                    null
                } else {
                    advice(
                        "LOAD",
                        AdviceKind.RISK,
                        "Нагрузка ${share(it.double(P.LOAD))} при пороге ${share(it.double(P.MAX_LOAD))}",
                        "С новым платежом компания отдаёт по кредитам больше, чем допускает политика.",
                    )
                }
            },
            MivarRule(
                "A_TERM",
                "Увеличение срока",
                listOf(P.LOAD_EXCEEDS, P.LOAD_MAX_TERM, P.MAX_LOAD, P.MAX_TERM, P.TERM, P.PAYMENT_MAX_TERM),
                listOf(
                    P.ADVICE + "TERM",
                ),
            ) {
                if (!it.flag(P.LOAD_EXCEEDS) || it.int(P.TERM) >= it.int(P.MAX_TERM) || it.double(P.LOAD_MAX_TERM) > it.double(P.MAX_LOAD)) {
                    null
                } else {
                    advice(
                        "TERM",
                        AdviceKind.OPTION,
                        "Предложить срок ${it.int(P.MAX_TERM)} мес.",
                        "Платёж снизится до ${rub(
                            it.long(P.PAYMENT_MAX_TERM),
                        )}, нагрузка — до ${share(it.double(P.LOAD_MAX_TERM))}: в пределах порога при той же сумме.",
                    )
                }
            },
            MivarRule(
                "A_AMOUNT",
                "Уменьшение суммы",
                listOf(P.LOAD_EXCEEDS, P.LOAD_MAX_TERM, P.MAX_LOAD, P.AFFORDABLE, P.MIN_AMOUNT, P.MAX_TERM),
                listOf(
                    P.ADVICE + "AMOUNT",
                ),
            ) {
                when {
                    !it.flag(P.LOAD_EXCEEDS) || it.double(P.LOAD_MAX_TERM) <= it.double(P.MAX_LOAD) -> null
                    it.long(P.AFFORDABLE) >= it.long(P.MIN_AMOUNT) -> advice(
                        "AMOUNT",
                        AdviceKind.OPTION,
                        "Предложить до ${rub(it.long(P.AFFORDABLE))} на ${it.int(P.MAX_TERM)} мес.",
                        "Даже на максимальный срок запрошенная сумма не проходит по нагрузке; эта сумма — в пределах порога.",
                    )
                    else -> advice(
                        "AMOUNT",
                        AdviceKind.RISK,
                        "По нагрузке не проходит даже минимальная сумма продукта",
                        "Свободной выручки под новый платёж не остаётся. Можно рассмотреть продукт с меньшим минимумом.",
                    )
                }
            },
            MivarRule("A_REVENUE", "Уточнить выручку", listOf(P.REVENUE_GAP_BIG, P.REVENUE_GAP), listOf(P.ADVICE + "REVENUE")) {
                if (!it.flag(P.REVENUE_GAP_BIG)) {
                    null
                } else {
                    advice(
                        "REVENUE",
                        AdviceKind.ASK,
                        "Уточнить выручку: расхождение с ФНС ${share(it.double(P.REVENUE_GAP))}",
                        "Запросить декларацию за последний год или пояснение, откуда другая сумма.",
                    )
                }
            },
            MivarRule("A_NO_ACCOUNT", "Нет счёта в банке", listOf(P.BANK_CLIENT), listOf(P.ADVICE + "NO_ACCOUNT")) {
                if (it.flag(P.BANK_CLIENT)) {
                    null
                } else {
                    advice(
                        "NO_ACCOUNT",
                        AdviceKind.ASK,
                        "Запросить выписку по счёту в другом банке",
                        "Счёта в банке нет: обороты и их стабильность не подтверждены.",
                    )
                }
            },
            MivarRule("A_TURNOVER", "Нестабильные обороты", listOf(P.TURNOVER_UNSTABLE, P.TURNOVER_VARIATION), listOf(P.ADVICE + "TURNOVER")) {
                if (!it.flag(P.TURNOVER_UNSTABLE)) {
                    null
                } else {
                    advice(
                        "TURNOVER",
                        AdviceKind.CHECK,
                        "Обороты колеблются ±${share(it.double(P.TURNOVER_VARIATION))}",
                        "Уточнить сезонность и крупные разовые поступления.",
                    )
                }
            },
            MivarRule("A_SEARCH", "Много запросов кредитной истории", listOf(P.CREDIT_SEARCH, P.INQUIRIES), listOf(P.ADVICE + "SEARCH")) {
                if (!it.flag(P.CREDIT_SEARCH)) {
                    null
                } else {
                    advice(
                        "SEARCH",
                        AdviceKind.CHECK,
                        "${it.int(P.INQUIRIES)} запросов кредитной истории за 30 дней",
                        "Уточнить, куда ещё подавались заявки: возможна новая нагрузка, которой нет в бюро.",
                    )
                }
            },
            MivarRule("A_PAST", "Просрочка в прошлом", listOf(P.PAST_DELAY, P.WORST_OVERDUE), listOf(P.ADVICE + "PAST")) {
                if (!it.flag(P.PAST_DELAY)) {
                    null
                } else {
                    advice(
                        "PAST",
                        AdviceKind.CHECK,
                        "Просрочка до ${it.int(P.WORST_OVERDUE)} дн. за 2 года, сейчас погашена",
                        "Уточнить причину и не повторится ли она.",
                    )
                }
            },
            MivarRule("A_TAX", "Налоговая задолженность", listOf(P.TAX_DEBT), listOf(P.ADVICE + "TAX")) {
                if (it.long(P.TAX_DEBT) <= 0) {
                    null
                } else {
                    advice(
                        "TAX",
                        AdviceKind.ASK,
                        "Недоимка по налогам ${rub(it.long(P.TAX_DEBT))}",
                        "Запросить подтверждение погашения или график.",
                    )
                }
            },
            MivarRule("A_YOUNG", "Молодой бизнес", listOf(P.YOUNG, P.AGE_MONTHS), listOf(P.ADVICE + "YOUNG")) {
                if (!it.flag(P.YOUNG)) {
                    null
                } else {
                    advice(
                        "YOUNG",
                        AdviceKind.RISK,
                        "Бизнесу ${it.long(P.AGE_MONTHS)} мес.",
                        "Короткая история: опираться на обороты и действующие договоры.",
                    )
                }
            },
            MivarRule("A_INDUSTRY", "Отрасль высокого риска", listOf(P.INDUSTRY_RISK), listOf(P.ADVICE + "INDUSTRY")) {
                if (it.getValue(P.INDUSTRY_RISK) != "HIGH") {
                    null
                } else {
                    advice(
                        "INDUSTRY",
                        AdviceKind.CHECK,
                        "Отрасль высокого риска",
                        "Оценить портфель заказов и зависимость от крупных заказчиков.",
                    )
                }
            },
            MivarRule("A_AUTO_LIMIT", "Выше порога автоодобрения", listOf(P.AMOUNT, P.AUTO_LIMIT), listOf(P.ADVICE + "AUTO_LIMIT")) {
                if (it.long(P.AMOUNT) <=
                    it.long(P.AUTO_LIMIT)
                ) {
                    null
                } else {
                    advice(
                        "AUTO_LIMIT",
                        AdviceKind.INFO,
                        "Сумма выше порога автоодобрения ${rub(it.long(P.AUTO_LIMIT))}",
                        "По регламенту решение принимает специалист.",
                    )
                }
            },
            MivarRule("A_RATING", "Рейтинг", listOf(P.RATING, P.SCORE), listOf(P.ADVICE + "RATING")) {
                when (it.getValue(P.RATING)) {
                    "C" -> advice(
                        "RATING",
                        AdviceKind.INFO,
                        "Рейтинг C, ${it.int(P.SCORE)} баллов — серая зона",
                        "Решение зависит от того, что покажут проверки ниже.",
                    )
                    "D", "E" -> advice(
                        "RATING",
                        AdviceKind.RISK,
                        "Рейтинг ${it.getValue(P.RATING)}, ${it.int(P.SCORE)} баллов",
                        "Риск выше, чем допускает продукт на запрошенных условиях.",
                    )
                    else -> null
                }
            },
        ),
    )

    private val order = AdviceKind.entries

    /** The advice of [facts] with the chain of statements behind each, most serious first. */
    fun advise(facts: Map<String, Any?>): Pair<List<Pair<AdviceItem, List<String>>>, Inference> {
        val inference = model.infer(facts)
        val items = inference.known.filterKeys { it.startsWith(P.ADVICE) }.map { (name, value) ->
            (value as AdviceItem) to inference.chain(name).dropLast(1).map { it.statement }
        }.sortedBy { order.indexOf(it.first.kind) }
        return items to inference
    }
}
