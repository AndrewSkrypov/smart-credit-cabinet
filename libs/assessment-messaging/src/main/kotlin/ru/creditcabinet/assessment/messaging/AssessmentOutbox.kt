package ru.creditcabinet.assessment.messaging

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Each side owns an assessment_exchange_outbox in its own database. */
class AssessmentOutbox(private val jdbc: NamedParameterJdbcTemplate, private val tx: TransactionTemplate) {
    data class Claim(val stream: String, val cycleId: UUID, val applicationId: UUID, val payload: String, val token: UUID, val attempts: Int)

    fun enqueue(stream: String, cycleId: UUID, applicationId: UUID, payload: String) {
        check(TransactionSynchronizationManager.isActualTransactionActive())
        val parameters = mapOf("stream" to stream, "cycle" to cycleId, "app" to applicationId, "payload" to payload)
        val inserted = jdbc.update(
            """
            insert into assessment_exchange_outbox (stream, cycle_id, application_id, payload)
            values (:stream, :cycle, :app, cast(:payload as jsonb))
            on conflict (stream, cycle_id) do nothing
            """.trimIndent(),
            parameters,
        )
        if (inserted == 0) {
            check(
                jdbc.queryForObject(
                    """
                    select application_id=:app and payload=cast(:payload as jsonb)
                    from assessment_exchange_outbox where stream=:stream and cycle_id=:cycle
                    """.trimIndent(),
                    parameters,
                    Boolean::class.java,
                ) == true,
            ) { "Assessment outbox key was reused" }
        }
    }

    fun claim(stream: String, now: Instant): Claim? = tx.execute {
        jdbc.query(
            """
            with candidate as (
                select cycle_id from assessment_exchange_outbox
                where stream=:stream and sent_at is null and available_at<=:now
                  and (locked_until is null or locked_until<=:now)
                order by available_at, cycle_id limit 1 for update skip locked
            )
            update assessment_exchange_outbox o
            set claim_token=:token, locked_until=:until, attempts=o.attempts+1
            from candidate c where o.stream=:stream and o.cycle_id=c.cycle_id
            returning o.stream,o.cycle_id,o.application_id,o.payload::text,o.claim_token,o.attempts
            """.trimIndent(),
            mapOf("stream" to stream, "now" to Timestamp.from(now), "token" to UUID.randomUUID(), "until" to Timestamp.from(now.plusSeconds(30))),
        ) { row, _ ->
            Claim(
                row.getString(1), row.getObject(2, UUID::class.java), row.getObject(3, UUID::class.java),
                row.getString(4), row.getObject(5, UUID::class.java), row.getInt(6),
            )
        }.singleOrNull()
    }

    fun finish(claim: Claim, now: Instant, errorCode: String?) {
        val delay = if (errorCode == null) 0L else (1L shl claim.attempts.coerceIn(0, 6)).coerceAtMost(60)
        jdbc.update(
            """
            update assessment_exchange_outbox
            set sent_at=:sent, last_error=:error, available_at=:next, claim_token=null, locked_until=null
            where stream=:stream and cycle_id=:cycle and claim_token=:token and sent_at is null and locked_until>:now
            """.trimIndent(),
            mapOf(
                "sent" to if (errorCode == null) Timestamp.from(now) else null,
                "error" to errorCode,
                "next" to Timestamp.from(now.plusSeconds(delay)),
                "stream" to claim.stream,
                "cycle" to claim.cycleId,
                "token" to claim.token,
                "now" to Timestamp.from(now),
            ),
        )
    }
}
