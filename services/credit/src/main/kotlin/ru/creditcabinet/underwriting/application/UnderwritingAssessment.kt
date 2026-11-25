package ru.creditcabinet.underwriting.application

import org.springframework.stereotype.Service
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatus
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
import ru.creditcabinet.underwriting.domain.Discrepancy
import ru.creditcabinet.underwriting.domain.UnderwritingDiscrepancies
import java.time.Instant
import java.util.UUID

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
        val registry = sources[SourceKind.REGISTRY] as RegistryInfo?
        val tax = sources[SourceKind.TAX] as TaxInfo?
        val bureau = sources[SourceKind.BUREAU] as BureauInfo?
        val actions = when {
            application.status != ApplicationStatus.MANUAL_REVIEW -> emptyList()
            claims.role == Roles.UNDERWRITER -> listOf("APPROVE", "COUNTER_OFFER", "REQUEST_INFO", "REJECT")
            claims.role == Roles.MANAGER -> listOf("REQUEST_INFO")
            else -> emptyList()
        }
        return AssessmentCard(
            applicationId = application.id,
            status = application.status,
            registry = registry,
            tax = tax,
            bureau = bureau,
            bank = bank,
            sourceFetchedAt = enrichment.fetchedAt(application.id),
            turnoverVariation = bank?.let { Scorecard.coefficientOfVariation(it.monthlyInflows) },
            discrepancies = UnderwritingDiscrepancies.compare(application.terms, tax, bureau, registry),
            automaticDecision = decisions.latestAutomatic(application.id),
            latestDecision = decisions.latest(application.id),
            actions = actions,
        )
    }
}
