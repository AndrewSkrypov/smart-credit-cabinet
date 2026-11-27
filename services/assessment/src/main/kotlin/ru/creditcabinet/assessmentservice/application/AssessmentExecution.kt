package ru.creditcabinet.assessmentservice.application

import ru.creditcabinet.assessment.contract.v1.AssessmentCompletedV1
import ru.creditcabinet.assessment.contract.v1.AssessmentOutcomeV1
import ru.creditcabinet.assessment.contract.v1.AssessmentRequestedV1
import ru.creditcabinet.assessment.contract.v1.NormalizedFactsV1
import ru.creditcabinet.assessment.domain.BankInfo
import ru.creditcabinet.assessment.domain.BureauInfo
import ru.creditcabinet.assessment.domain.RegistryInfo
import ru.creditcabinet.assessment.domain.SnapshotEvaluation
import ru.creditcabinet.assessment.domain.SourceKind
import ru.creditcabinet.assessment.domain.TaxInfo
import java.time.Clock
import java.time.Instant
import java.util.UUID

interface AssessmentSources {
    fun bank(inn: String): BankInfo
    fun registry(inn: String): RegistryInfo
    fun tax(inn: String): TaxInfo
    fun bureau(inn: String, consentId: UUID): BureauInfo
}

class AssessmentSourceUnavailable : RuntimeException()

data class CollectedFacts(val facts: NormalizedFactsV1, val receipts: Map<SourceKind, Instant>) {
    companion object {
        fun initial(request: AssessmentRequestedV1): CollectedFacts {
            val bank = request.bankPrefetch
            return CollectedFacts(
                NormalizedFactsV1(bank = bank?.fact),
                bank?.let { mapOf(SourceKind.BANK to it.receivedAt) }.orEmpty(),
            )
        }
    }
}

data class ExecutionProgress(val collection: CollectedFacts, val result: AssessmentCompletedV1?)

class AssessmentExecution(
    private val sources: AssessmentSources,
    private val evaluation: SnapshotEvaluation,
    private val clock: Clock,
) {
    fun process(request: AssessmentRequestedV1, progress: CollectedFacts): ExecutionProgress {
        var facts = progress.facts
        val receipts = progress.receipts.toMutableMap()
        for (source in SourceKind.entries) {
            if (source in receipts || (source == SourceKind.BUREAU && request.bureauConsent == null)) continue
            if (clock.instant() >= request.deadline) break

            val response = try {
                when (source) {
                    SourceKind.BANK -> facts.copy(bank = sources.bank(request.inn))
                    SourceKind.REGISTRY -> facts.copy(registry = sources.registry(request.inn))
                    SourceKind.TAX -> facts.copy(tax = sources.tax(request.inn))
                    SourceKind.BUREAU -> facts.copy(bureau = sources.bureau(request.inn, checkNotNull(request.bureauConsent).applicationId))
                }
            } catch (_: AssessmentSourceUnavailable) {
                continue
            }
            val receivedAt = clock.instant()
            if (receivedAt >= request.deadline) break
            if (source == SourceKind.REGISTRY && response.registry?.inn != request.inn) continue
            facts = response
            receipts[source] = receivedAt
        }
        val collected = CollectedFacts(facts, receipts)
        val missing = SourceKind.entries.toSet() - receipts.keys
        val completedAt = clock.instant()
        val allFacts = facts.complete()
        val result = when {
            allFacts != null -> {
                val evaluated = evaluation.evaluate(request, allFacts, completedAt)
                AssessmentCompletedV1(
                    request.cycle, receipts, facts, AssessmentOutcomeV1.ASSESSED, missing, completedAt,
                    evaluated.assessment, evaluated.policy, evaluated.appliedPreApprovalId,
                )
            }
            (request.bureauConsent == null && missing == setOf(SourceKind.BUREAU)) || completedAt >= request.deadline ->
                AssessmentCompletedV1(request.cycle, receipts, facts, AssessmentOutcomeV1.INCOMPLETE, missing, completedAt, null, null, null)
            else -> null
        }
        result?.validateAgainst(request)
        return ExecutionProgress(collected, result)
    }
}
