package ru.creditcabinet.assessment.domain

import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.product.domain.LoanMath
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductMatcher.rubles
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Conditions that refuse an application whatever the score (docs/process-model.md, 6.1). */
enum class StopFactor(val clientText: String, val clientHint: String) {
    SF_STATUS("По данным ЕГРЮЛ/ЕГРИП компания не действует", "Проверьте сведения о компании в реестре"),
    SF_UNRELIABLE("В реестре есть отметка о недостоверных сведениях", "Обновите сведения в налоговой и подайте заявку снова"),
    SF_OVERDUE_90("Есть просроченная более 90 дней задолженность", "Погасите просрочку — после этого заявку можно подать снова"),
    SF_TOO_YOUNG("Бизнес зарегистрирован меньше 3 месяцев назад", "Подайте заявку позже или выберите бизнес-карту с небольшим лимитом"),
}

enum class Factor(val title: String) {
    AGE("Возраст бизнеса"),
    TURNOVER_STABILITY("Стабильность оборотов"),
    DEBT_LOAD("Долговая нагрузка"),
    PAYMENT_DISCIPLINE("Платёжная дисциплина"),
    CREDIT_ACTIVITY("Кредитная активность"),
    TAX_DISCIPLINE("Налоговая дисциплина"),
    INDUSTRY_RISK("Отраслевой риск"),
    REQUEST_SIZE("Размер запроса"),
}

/** One line of the scorecard: what was measured, the points it gave and, if not the best, what would help. */
data class FactorScore(
    val factor: Factor,
    val title: String,
    val value: String,
    val points: Int,
    val maxPoints: Int,
    val hint: String?,
)

/** Where the revenue used in the ratios came from. */
enum class RevenueSource { TAX, TURNOVER, DECLARED }

data class Assessment(
    val stopFactors: List<StopFactor>,
    val factors: List<FactorScore>,
    val score: Int,
    val rating: Rating,
    val annualRevenue: Long,
    val revenueSource: RevenueSource,
    val existingMonthlyPayments: Long,
    val businessAgeMonths: Long,
    val industryRisk: IndustryRisk,
    /** The registry's own words for a company that is not active: «В процессе ликвидации». */
    val registryStatusTitle: String,
) {
    val monthlyRevenue: Double get() = annualRevenue / 12.0

    /** What the client reads for each stop factor; an inactive company is told what the registry says. */
    fun stopFactorTexts(): List<String> = stopFactors.map { factor ->
        val status = registryStatusTitle.trim().replaceFirstChar { it.lowercase() }
        val text = if (factor == StopFactor.SF_STATUS && status.isNotEmpty()) "${factor.clientText}: $status" else factor.clientText
        "$text. ${factor.clientHint}"
    }

    /** The factors that cost the most points, worst first: what a client is told after a refusal. */
    fun weakest(count: Int): List<FactorScore> = factors.filter { it.points < it.maxPoints }.sortedBy { it.points.toDouble() / it.maxPoints }.take(count)
}

/**
 * The scorecard of docs/process-model.md, section 6: stop factors, eight factors of 100 points
 * in total, and the rating. A plain, explainable model on purpose: every point can be traced to
 * a number from a source, which a model trained without default history could not offer.
 */
object Scorecard {
    fun assess(terms: ApplicationTerms, product: Product, data: SourceData, rules: RuleSet, today: LocalDate): Assessment {
        val ageMonths = ChronoUnit.MONTHS.between(data.registry.registrationDate, today)
        val stopFactors = buildList {
            if (data.registry.status != "ACTIVE") add(StopFactor.SF_STATUS)
            if (data.registry.unreliableAddress || data.registry.unreliableDirector) add(StopFactor.SF_UNRELIABLE)
            if (data.bureau.currentOverdueDaysMax > 90) add(StopFactor.SF_OVERDUE_90)
            if (ageMonths < 3) add(StopFactor.SF_TOO_YOUNG)
        }
        val (revenue, revenueSource) = revenue(data, terms)
        val monthlyRevenue = revenue / 12.0
        val existing = data.bureau.monthlyPaymentsTotal
        val newPayment = if (product.amountFits(terms.amount) && product.termFits(terms.termMonths)) {
            LoanMath.calculate(terms.amount, product.baseRate, terms.termMonths, product.revolving).monthlyPayment.toDouble()
        } else {
            0.0
        }
        val load = if (monthlyRevenue > 0) (existing + newPayment) / monthlyRevenue else Double.MAX_VALUE
        val cv = coefficientOfVariation(data.bank.monthlyInflows)
        val industry = rules.industryRisk(data.registry.okvedCode)
        val requestShare = if (revenue > 0) terms.amount.toDouble() / revenue else Double.MAX_VALUE
        val factors = listOf(
            age(ageMonths),
            stability(cv),
            load(load),
            discipline(data.bureau.worstOverdueDays24m),
            activity(data.bureau.inquiries30d),
            tax(data.tax.taxDebt),
            industry(industry, data.registry.okvedCode),
            requestSize(requestShare, revenue),
        )
        val score = factors.sumOf { it.points }
        return Assessment(stopFactors, factors, score, rules.ratingOf(score), revenue, revenueSource, existing, ageMonths, industry, data.registry.statusTitle)
    }

    /** Revenue by the tax service; without reports — by turnover on the account; otherwise what the client declared. */
    fun revenue(data: SourceData, terms: ApplicationTerms): Pair<Long, RevenueSource> =
        when {
            data.tax.hasReports && data.tax.revenue != null && data.tax.revenue > 0 -> data.tax.revenue to RevenueSource.TAX
            data.bank.monthlyInflows.size >= 12 -> data.bank.monthlyInflows.sum() to RevenueSource.TURNOVER
            else -> (terms.declaredRevenue12m ?: 0L) to RevenueSource.DECLARED
        }

    fun coefficientOfVariation(values: List<Long>): Double? {
        if (values.size < 12) return null
        val mean = values.average()
        if (mean <= 0) return null
        return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size) / mean
    }

    private fun age(months: Long) =
        FactorScore(
            Factor.AGE,
            Factor.AGE.title,
            "${months / 12} г. ${months % 12} мес.",
            when {
                months < 12 -> 0
                months < 36 -> 8
                months < 60 -> 14
                else -> 18
            },
            18,
            if (months < 60) "Чем дольше работает бизнес, тем ниже риск — это изменится со временем" else null,
        )

    private fun stability(cv: Double?) =
        FactorScore(
            Factor.TURNOVER_STABILITY,
            Factor.TURNOVER_STABILITY.title,
            cv?.let { "колебания выручки ±${(it * 100).roundToInt()}%" } ?: "нет оборотов по счёту в банке",
            when {
                cv == null -> 0
                cv < 0.2 -> 12
                cv < 0.4 -> 8
                cv < 0.6 -> 4
                else -> 0
            },
            12,
            if (cv ==
                null
            ) {
                "Оборот через счёт в банке позволяет оценить стабильность выручки"
            } else if (cv >=
                0.2
            ) {
                "Ровные поступления по счёту повышают оценку"
            } else {
                null
            },
        )

    private fun load(load: Double) =
        FactorScore(
            Factor.DEBT_LOAD,
            Factor.DEBT_LOAD.title,
            if (load == Double.MAX_VALUE) "выручка неизвестна" else "платежи по кредитам — ${(load * 100).roundToInt()}% месячной выручки",
            when {
                load < 0.10 -> 20
                load < 0.20 -> 14
                load < 0.35 -> 6
                else -> 0
            },
            20,
            if (load >= 0.10) "Уменьшите сумму, увеличьте срок или погасите часть текущих кредитов" else null,
        )

    private fun discipline(worstOverdue: Int) =
        FactorScore(
            Factor.PAYMENT_DISCIPLINE,
            Factor.PAYMENT_DISCIPLINE.title,
            if (worstOverdue == 0) "просрочек за 2 года нет" else "худшая просрочка за 2 года — $worstOverdue дн.",
            when {
                worstOverdue == 0 -> 18
                worstOverdue <= 30 -> 10
                else -> 2
            },
            18,
            if (worstOverdue > 0) "Платежи без просрочек постепенно улучшают кредитную историю" else null,
        )

    private fun activity(inquiries: Int) =
        FactorScore(
            Factor.CREDIT_ACTIVITY,
            Factor.CREDIT_ACTIVITY.title,
            "запросов кредитной истории за 30 дней: $inquiries",
            when {
                inquiries <= 2 -> 6
                inquiries <= 5 -> 3
                else -> 0
            },
            6,
            if (inquiries > 2) "Много заявок в разные банки за короткий срок снижают оценку" else null,
        )

    private fun tax(debt: Long) =
        FactorScore(
            Factor.TAX_DISCIPLINE,
            Factor.TAX_DISCIPLINE.title,
            if (debt == 0L) "задолженности по налогам нет" else "задолженность по налогам — ${rubles(debt)}",
            when {
                debt == 0L -> 8
                debt < 100_000 -> 4
                else -> 0
            },
            8,
            if (debt > 0) "Погасите налоговую задолженность" else null,
        )

    private fun industry(risk: IndustryRisk, okved: String) =
        FactorScore(
            Factor.INDUSTRY_RISK,
            Factor.INDUSTRY_RISK.title,
            "ОКВЭД $okved, риск ${risk.title}",
            when (risk) {
                IndustryRisk.LOW -> 8
                IndustryRisk.MEDIUM -> 5
                IndustryRisk.HIGH -> 0
            },
            8,
            null,
        )

    private fun requestSize(share: Double, revenue: Long) =
        FactorScore(
            Factor.REQUEST_SIZE,
            Factor.REQUEST_SIZE.title,
            if (revenue > 0) "сумма — ${(share * 100).roundToInt()}% годовой выручки" else "выручка неизвестна",
            when {
                share < 0.15 -> 10
                share < 0.30 -> 6
                share < 0.50 -> 2
                else -> 0
            },
            10,
            if (share >= 0.15) "Сумма меньше 15% годовой выручки оценивается лучше" else null,
        )
}
