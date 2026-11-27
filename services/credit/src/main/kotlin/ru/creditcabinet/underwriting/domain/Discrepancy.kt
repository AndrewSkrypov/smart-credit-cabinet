package ru.creditcabinet.underwriting.domain

import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.assessment.domain.BureauInfo
import ru.creditcabinet.assessment.domain.RegistryInfo
import ru.creditcabinet.assessment.domain.TaxInfo
import ru.creditcabinet.product.domain.ProductMatcher.rubles
import kotlin.math.abs
import kotlin.math.roundToInt

/** The difference between a declared value and the value received from a source. */
data class Discrepancy(val field: String, val declared: String, val source: String, val actual: String, val differencePercent: Int?)

object UnderwritingDiscrepancies {
    private const val REVENUE_TOLERANCE = 0.05

    fun compare(terms: ApplicationTerms, tax: TaxInfo?, bureau: BureauInfo?, registry: RegistryInfo?): List<Discrepancy> = buildList {
        val declaredRevenue = terms.declaredRevenue12m
        val taxRevenue = tax?.revenue
        if (declaredRevenue != null && taxRevenue != null && taxRevenue > 0) {
            val difference = abs(declaredRevenue - taxRevenue).toDouble() / taxRevenue
            if (difference > REVENUE_TOLERANCE) {
                add(Discrepancy("Выручка за год", rubles(declaredRevenue), "ФНС", rubles(taxRevenue), (difference * 100).roundToInt()))
            }
        }
        val declaredDebt = terms.declaredMonthlyDebtPayments
        if (bureau != null && declaredDebt != null && abs(declaredDebt - bureau.monthlyPaymentsTotal) > maxOf(10_000L, bureau.monthlyPaymentsTotal / 10)) {
            add(Discrepancy("Платежи по кредитам в месяц", rubles(declaredDebt), "БКИ", rubles(bureau.monthlyPaymentsTotal), null))
        }
        if (registry != null && registry.status != "ACTIVE") {
            add(Discrepancy("Статус компании", "действует", "ЕГРЮЛ/ЕГРИП", registry.statusTitle, null))
        }
    }
}
