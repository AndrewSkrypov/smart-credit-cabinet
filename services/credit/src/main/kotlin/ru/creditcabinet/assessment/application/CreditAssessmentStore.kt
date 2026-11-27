package ru.creditcabinet.assessment.application

import ru.creditcabinet.application.domain.PipelineJob
import ru.creditcabinet.assessment.contract.v1.AssessmentCompletedV1
import ru.creditcabinet.assessment.contract.v1.AssessmentRequestedV1
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class AssessmentRouting(val enabled: Boolean, val stream: String, val resultGrace: Duration)

data class CreditAssessmentCycle(
    val id: UUID,
    val request: AssessmentRequestedV1?,
    val result: AssessmentCompletedV1?,
    val acceptUntil: Instant?,
    val scoreVersion: Long?,
    val closedAt: Instant?,
)

interface CreditAssessmentStore {
    /** Application first, then the current claimed attempt. Held through the surrounding transaction. */
    fun lock(job: PipelineJob): Boolean

    fun cycle(id: UUID): CreditAssessmentCycle?
    fun pendingScore(applicationId: UUID): CreditAssessmentCycle?
    fun pin(job: PipelineJob, version: Long, request: AssessmentRequestedV1?, acceptUntil: Instant?)
    fun markScoring(id: UUID, version: Long)
    fun close(id: UUID, at: Instant)
    fun finishJob(job: PipelineJob)
    fun reschedule(job: PipelineJob, at: Instant, error: String? = null)
    fun failJob(job: PipelineJob, error: String?)
}
