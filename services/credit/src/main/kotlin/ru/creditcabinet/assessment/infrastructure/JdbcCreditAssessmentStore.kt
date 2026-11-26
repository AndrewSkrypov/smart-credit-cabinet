package ru.creditcabinet.assessment.infrastructure

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.application.domain.PipelineJob
import ru.creditcabinet.assessment.application.CreditAssessmentCycle
import ru.creditcabinet.assessment.application.CreditAssessmentStore
import ru.creditcabinet.assessment.contract.v1.AssessmentCompletedV1
import ru.creditcabinet.assessment.contract.v1.AssessmentRequestedV1
import ru.creditcabinet.assessment.messaging.AssessmentJson
import ru.creditcabinet.assessment.messaging.AssessmentOutbox
import ru.creditcabinet.assessment.messaging.InvalidAssessmentEnvelope
import ru.creditcabinet.assessment.messaging.validateAssessmentEnvelope
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

class JdbcCreditAssessmentStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val json: AssessmentJson,
    private val outbox: AssessmentOutbox,
    private val clock: Clock,
) : CreditAssessmentStore {
    private val rowMapper = RowMapper { row, _ ->
        CreditAssessmentCycle(
            row.getObject("cycle_id", UUID::class.java),
            row.getString("request")?.let { json.read(it, AssessmentRequestedV1::class.java) },
            row.getString("result")?.let { json.read(it, AssessmentCompletedV1::class.java) },
            row.getTimestamp("accept_until")?.toInstant(),
            (row.getObject("score_version") as? Number)?.toLong(),
            row.getTimestamp("closed_at")?.toInstant(),
        )
    }

    override fun lock(job: PipelineJob): Boolean {
        val application = jdbc.query(
            "select id from applications where id=:app for update",
            mapOf("app" to job.applicationId),
        ) { _, _ -> true }.isNotEmpty()
        if (!application) return false
        val lease = jdbc.query(
            """
            select locked_until from pipeline_jobs where id=:job and application_id=:app
                and step=:step and status='RUNNING' and attempts=:attempt for update
            """.trimIndent(),
            mapOf("job" to job.id, "app" to job.applicationId, "step" to job.step.name, "attempt" to job.attempts),
        ) { row, _ -> row.getTimestamp(1)?.toInstant() }.firstOrNull()
        return lease != null && lease > clock.instant()
    }

    override fun cycle(id: UUID): CreditAssessmentCycle? = jdbc.query(
        "select * from credit_assessment_cycle where cycle_id=:id for update",
        mapOf("id" to id),
        rowMapper,
    ).firstOrNull()

    override fun pendingScore(applicationId: UUID): CreditAssessmentCycle? = jdbc.query(
        "select * from credit_assessment_cycle where application_id=:id and score_version is not null and closed_at is null for update",
        mapOf("id" to applicationId),
        rowMapper,
    ).singleOrNull()

    override fun pin(job: PipelineJob, version: Long, request: AssessmentRequestedV1?, acceptUntil: Instant?) {
        val payload = request?.let(json::write)
        jdbc.update(
            """
            insert into credit_assessment_cycle(cycle_id,application_id,application_version,stream,request,accept_until)
            values (:cycle,:app,:version,:stream,cast(:request as jsonb),:until)
            """.trimIndent(),
            mapOf(
                "cycle" to job.id,
                "app" to job.applicationId,
                "version" to version,
                "stream" to request?.cycle?.generation,
                "request" to payload,
                "until" to acceptUntil?.let(Timestamp::from),
            ),
        )
        if (request != null && payload != null) outbox.enqueue(request.cycle.generation, job.id, job.applicationId, payload)
    }

    fun receive(result: AssessmentCompletedV1, payload: String) = tx.executeWithoutResult {
        val cycle = cycle(result.cycle.cycleId) ?: return@executeWithoutResult
        val request = cycle.request ?: return@executeWithoutResult
        val acceptUntil = checkNotNull(cycle.acceptUntil)
        val now = clock.instant()
        if (cycle.closedAt != null || cycle.scoreVersion != null || now >= acceptUntil) return@executeWithoutResult
        validateAssessmentEnvelope {
            result.validateAgainst(request)
            require(result.completedAt < acceptUntil && result.completedAt <= now.plusSeconds(5))
        }
        val parameters = mapOf("id" to cycle.id, "payload" to payload, "now" to Timestamp.from(now))
        if (cycle.result != null) {
            val duplicate = jdbc.queryForObject(
                "select result=cast(:payload as jsonb) from credit_assessment_cycle where cycle_id=:id",
                parameters,
                Boolean::class.java,
            )
            if (duplicate != true) throw InvalidAssessmentEnvelope()
            return@executeWithoutResult
        }
        jdbc.update(
            "update credit_assessment_cycle set result=cast(:payload as jsonb),received_at=:now where cycle_id=:id",
            parameters,
        )
    }

    override fun markScoring(id: UUID, version: Long) {
        check(
            jdbc.update(
                "update credit_assessment_cycle set score_version=:version where cycle_id=:id and result is not null and closed_at is null",
                mapOf("id" to id, "version" to version),
            ) == 1,
        )
    }

    override fun close(id: UUID, at: Instant) {
        check(
            jdbc.update(
                "update credit_assessment_cycle set closed_at=:at where cycle_id=:id and closed_at is null",
                mapOf("id" to id, "at" to Timestamp.from(at)),
            ) == 1,
        )
    }

    override fun finishJob(job: PipelineJob) {
        check(updateJob(job, "DONE", null, null) == 1) { "Assessment job lease expired before commit" }
    }
    override fun reschedule(job: PipelineJob, at: Instant, error: String?) {
        updateJob(job, "PENDING", at, error)
    }
    override fun failJob(job: PipelineJob, error: String?) {
        updateJob(job, "FAILED", null, error)
    }

    private fun updateJob(job: PipelineJob, status: String, at: Instant?, error: String?): Int =
        jdbc.update(
            """
            update pipeline_jobs set status=:status,run_at=coalesce(:at,run_at),locked_until=null,updated_at=now(),last_error=:error
            where id=:id and application_id=:app and step=:step and status='RUNNING' and attempts=:attempt and locked_until>:now
            """.trimIndent(),
            mapOf(
                "id" to job.id,
                "app" to job.applicationId,
                "step" to job.step.name,
                "attempt" to job.attempts,
                "status" to status,
                "at" to at?.let(Timestamp::from),
                "error" to error?.take(2000),
                "now" to Timestamp.from(clock.instant()),
            ),
        )
}
