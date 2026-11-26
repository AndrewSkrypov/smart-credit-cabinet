package ru.creditcabinet.assessmentservice.infrastructure

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.assessment.contract.v1.AssessmentRequestedV1
import ru.creditcabinet.assessment.messaging.AssessmentJson
import ru.creditcabinet.assessment.messaging.AssessmentOutbox
import ru.creditcabinet.assessment.messaging.InvalidAssessmentEnvelope
import ru.creditcabinet.assessmentservice.application.CollectedFacts
import ru.creditcabinet.assessmentservice.application.ExecutionProgress
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class AssessmentExecutions(
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val json: AssessmentJson,
    private val outbox: AssessmentOutbox,
    private val clock: Clock,
    private val stream: String,
) {
    data class Claim(val request: AssessmentRequestedV1, val collection: CollectedFacts, val token: UUID, val attempts: Int)

    fun accept(request: AssessmentRequestedV1, payload: String) {
        val scope = request.cycle
        val parameters = mapOf("stream" to stream, "id" to scope.cycleId, "app" to scope.applicationId, "request" to payload)
        tx.executeWithoutResult {
            val inserted = jdbc.update(
                """
                insert into assessment_execution (stream,cycle_id,application_id,request)
                values (:stream,:id,:app,cast(:request as jsonb))
                on conflict (stream,cycle_id) do nothing
                """.trimIndent(),
                parameters,
            )
            if (inserted == 0 &&
                jdbc.queryForObject(
                    "select request=cast(:request as jsonb) from assessment_execution where stream=:stream and cycle_id=:id",
                    parameters,
                    Boolean::class.java,
                ) != true
            ) {
                throw InvalidAssessmentEnvelope()
            }
        }
    }

    fun claim(): Claim? = tx.execute {
        val now = currentTime()
        jdbc.query(
            """
            with candidate as (
                select cycle_id from assessment_execution
                where stream=:stream and completed_at is null and available_at<=:now
                  and (locked_until is null or locked_until<=:now)
                order by available_at,cycle_id limit 1 for update skip locked
            )
            update assessment_execution e set locked_until=:lease,claim_token=:token,attempts=e.attempts+1
            from candidate c where e.stream=:stream and e.cycle_id=c.cycle_id
            returning e.request::text,e.progress::text,e.claim_token,e.attempts
            """.trimIndent(),
            mapOf("stream" to stream, "now" to Timestamp.from(now), "lease" to Timestamp.from(now.plusSeconds(45)), "token" to UUID.randomUUID()),
        ) { row, _ ->
            val request = json.read(row.getString(1), AssessmentRequestedV1::class.java)
            Claim(
                request,
                row.getString(2)?.let { json.readState(it, CollectedFacts::class.java) } ?: CollectedFacts.initial(request),
                row.getObject(3, UUID::class.java),
                row.getInt(4),
            )
        }.singleOrNull()
    }

    fun finish(claim: Claim, progress: ExecutionProgress?): Boolean = tx.execute {
        val key = mapOf("stream" to stream, "id" to claim.request.cycle.cycleId, "token" to claim.token)
        val lease = jdbc.query(
            """
            select locked_until from assessment_execution where stream=:stream and cycle_id=:id
                and completed_at is null and claim_token=:token for update
            """.trimIndent(),
            key,
        ) { row, _ -> row.getTimestamp(1).toInstant() }.firstOrNull()
        val now = currentTime()
        if (lease == null || lease <= now) return@execute false
        val result = progress?.result
        val resultJson = result?.let(json::write)
        val delay = (1L shl claim.attempts.coerceIn(0, 6)).coerceAtMost(30)
        val retryAt = if (now >= claim.request.deadline) now.plusSeconds(delay) else minOf(now.plusSeconds(delay), claim.request.deadline)
        val updated = jdbc.update(
            """
            update assessment_execution set progress=cast(:progress as jsonb),result=cast(:result as jsonb),
                completed_at=:completed,available_at=:retry,locked_until=null,claim_token=null,last_error=:error
            where stream=:stream and cycle_id=:id and claim_token=:token and completed_at is null
            """.trimIndent(),
            key + mapOf(
                "progress" to json.write(progress?.collection ?: claim.collection),
                "result" to resultJson,
                "completed" to result?.let { Timestamp.from(it.completedAt) },
                "retry" to Timestamp.from(retryAt),
                "error" to if (progress == null) {
                    "EXECUTION_FAILED"
                } else if (result == null) {
                    "SOURCES_PENDING"
                } else {
                    null
                },
            ),
        )
        check(updated == 1)
        if (resultJson != null) {
            outbox.enqueue(stream, claim.request.cycle.cycleId, claim.request.cycle.applicationId, resultJson)
        }
        true
    } == true

    private fun currentTime(): Instant = clock.instant().truncatedTo(ChronoUnit.MICROS)
}
