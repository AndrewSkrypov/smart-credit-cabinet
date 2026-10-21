package ru.creditcabinet.assessment.domain

import ru.creditcabinet.application.domain.ActorType
import java.time.Instant
import java.util.UUID

enum class DecisionOutcome { APPROVE, COUNTER_OFFER, MANUAL_REVIEW, REJECT, REQUEST_INFO }

/** What the rules propose, stored with the decision: offer terms, or a refusal with its reason. */
data class Recommendation(
    val outcome: DecisionOutcome,
    val terms: OfferTerms? = null,
    val rejectReason: RejectReason? = null,
    val notes: List<String> = emptyList(),
)

/**
 * One decision on an application, automatic or by an underwriter, with everything needed to
 * reproduce it: the rule set version, the inputs, every factor.
 */
data class Decision(
    val id: UUID = UUID.randomUUID(),
    val applicationId: UUID,
    val ruleSetVersion: Int,
    val decidedByType: ActorType,
    val decidedBy: UUID?,
    val score: Int?,
    val rating: Rating?,
    val stopFactors: List<StopFactor>,
    val factors: List<FactorScore>,
    val outcome: DecisionOutcome,
    val reasonCode: String?,
    val manualReasons: List<ManualReason>,
    val recommendation: Recommendation?,
    val inputs: Map<String, Any?>,
    val comment: String?,
    val createdAt: Instant,
)

interface DecisionRepository {
    fun save(decision: Decision)
    fun find(id: UUID): Decision?
    fun latest(applicationId: UUID): Decision?
    fun latestAutomatic(applicationId: UUID): Decision?
}

interface EnrichmentStore {
    fun save(applicationId: UUID, kind: SourceKind, data: Any, at: Instant)

    /** What has been fetched for the application so far, by source. */
    fun load(applicationId: UUID): Map<SourceKind, Any>
}
