package ru.creditcabinet.underwriting.application

import org.springframework.stereotype.Service
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.service.notFound
import ru.creditcabinet.assessment.domain.BankInfo
import ru.creditcabinet.assessment.domain.BureauInfo
import ru.creditcabinet.assessment.domain.Decision
import ru.creditcabinet.assessment.domain.DecisionRepository
import ru.creditcabinet.assessment.domain.EnrichmentStore
import ru.creditcabinet.assessment.domain.RegistryInfo
import ru.creditcabinet.assessment.domain.Scorecard
import ru.creditcabinet.assessment.domain.SourceKind
import ru.creditcabinet.assessment.domain.TaxInfo
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.product.domain.ProductMatcher.rubles
import ru.creditcabinet.underwriting.domain.Discrepancy
import java.time.Instant
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

data class AssessmentCard(
    val applicationId: UUID,
    val status: ApplicationStatus,
    val registry: RegistryInfo?,
    val tax: TaxInfo?,
    val bureau: BureauInfo?,
    val bank: BankInfo?,
    val sourceFetchedAt: Map<SourceKind, Instant>,
    val turnoverVariation: Double?,
    val discrepancies: List<Discrepancy>,
    val automaticDecision: Decision?,
    val latestDecision: Decision?,
    val actions: List<String>,
)

fun interface UnderwritingAssessment {
    fun read(id: UUID): AssessmentCard
}

@Service
class ReadUnderwritingAssessment(
    private val applications: ApplicationRepository,
    private val enrichment: EnrichmentStore,
    private val decisions: DecisionRepository,
) : UnderwritingAssessment {
    override fun read(id: UUID): AssessmentCard {
        val claims = CurrentCaller.requireBankStaff()
        val application = applications.find(id)?.takeIf { it.visibleToBank } ?: throw notFound()
        val sources = enrichment.load(application.id)
        val bank = sources[SourceKind.BANK] as BankInfo?
        val actions = when {
            application.status != ApplicationStatus.MANUAL_REVIEW -> emptyList()
            claims.role == Roles.UNDERWRITER -> listOf("APPROVE", "COUNTER_OFFER", "REQUEST_INFO", "REJECT")
            claims.role == Roles.MANAGER -> listOf("REQUEST_INFO")
            else -> emptyList()
        }
        return AssessmentCard(
            applicationId = application.id,
            status = application.status,
            registry = sources[SourceKind.REGISTRY] as RegistryInfo?,
            tax = sources[SourceKind.TAX] as TaxInfo?,
            bureau = sources[SourceKind.BUREAU] as BureauInfo?,
            bank = bank,
            sourceFetchedAt = enrichment.fetchedAt(application.id),
            turnoverVariation = bank?.let { Scorecard.coefficientOfVariation(it.monthlyInflows) },
            discrepancies = discrepancies(application, sources),
            automaticDecision = decisions.latestAutomatic(application.id),
            latestDecision = decisions.latest(application.id),
            actions = actions,
        )
    }

    private fun discrepancies(application: LoanApplication, sources: Map<SourceKind, Any>): List<Discrepancy> =
        buildList {
            val tax = sources[SourceKind.TAX] as TaxInfo?
            val declaredRevenue = application.terms.declaredRevenue12m
            if (declaredRevenue != null && tax?.revenue != null && tax.revenue > 0) {
                val difference = abs(declaredRevenue - tax.revenue).toDouble() / tax.revenue
                if (difference > NOTICEABLE) {
                    add(Discrepancy("Выручка за год", rubles(declaredRevenue), "ФНС", rubles(tax.revenue), (difference * 100).roundToInt()))
                }
            }
            val bureau = sources[SourceKind.BUREAU] as BureauInfo?
            val declaredDebt = application.terms.declaredMonthlyDebtPayments
            if (bureau != null && declaredDebt != null && abs(declaredDebt - bureau.monthlyPaymentsTotal) > maxOf(10_000L, bureau.monthlyPaymentsTotal / 10)) {
                add(Discrepancy("Платежи по кредитам в месяц", rubles(declaredDebt), "БКИ", rubles(bureau.monthlyPaymentsTotal), null))
            }
            val registry = sources[SourceKind.REGISTRY] as RegistryInfo?
            if (registry != null && registry.status != "ACTIVE") add(Discrepancy("Статус компании", "действует", "ЕГРЮЛ/ЕГРИП", registry.statusTitle, null))
        }

    private companion object {
        const val NOTICEABLE = 0.05
    }
}
