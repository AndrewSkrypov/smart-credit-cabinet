package ru.creditcabinet.common.outbox

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class DeliveryClaim(val id: UUID, val channel: String, val payload: String, val attempts: Int, val token: UUID, val traceParent: String? = null)

enum class DeliveryOutboxTable(internal val sqlName: String) {
    NOTIFICATIONS("delivery_outbox"),
    APPLICATION_STATUS("application_status_outbox"),
}

/** Shared SQL protocol; each sending service owns the migration in its own database. */
class DurableOutbox(
    private val jdbc: NamedParameterJdbcTemplate,
    private val transaction: TransactionTemplate,
    private val mapper: ObjectMapper,
    private val tracing: OutboxTracing? = null,
    table: DeliveryOutboxTable = DeliveryOutboxTable.NOTIFICATIONS,
) {
    private val tableName = table.sqlName

    fun enqueue(channel: String, key: String, payload: String): UUID {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "Outbox enqueue requires the business transaction" }
        require(channel.length in 1..32 && key.length in 1..255) { "Invalid outbox address" }
        val id = UUID.randomUUID()
        val parameters = mapOf("id" to id, "channel" to channel, "key" to key, "payload" to payload, "traceParent" to tracing?.traceParent())
        val inserted = jdbc.query(
            """
            insert into $tableName (id, channel, event_key, payload, trace_parent)
            values (:id, :channel, :key, cast(:payload as jsonb), :traceParent)
            on conflict (channel, event_key) do nothing returning id
            """.trimIndent(),
            parameters,
        ) { rs, _ -> rs.getObject("id", UUID::class.java) }.firstOrNull()
        if (inserted != null) return inserted
        return jdbc.query(
            "select id, payload::text from $tableName where channel=:channel and event_key=:key",
            parameters,
        ) { rs, _ ->
            require(mapper.readTree(rs.getString("payload")) == mapper.readTree(payload)) { "Outbox key already belongs to another payload" }
            rs.getObject("id", UUID::class.java)
        }.single()
    }

    fun claim(now: Instant, lease: Duration, maxAttempts: Int): DeliveryClaim? = transaction.execute {
        val parameters = mapOf("now" to Timestamp.from(now), "until" to Timestamp.from(now.plus(lease)), "max" to maxAttempts, "token" to UUID.randomUUID())
        jdbc.update(
            """
            update $tableName set state='FAILED', claim_token=null, locked_until=null, updated_at=:now
            where attempts >= :max and ((state='RUNNING' and locked_until <= :now) or state='PENDING')
            """.trimIndent(),
            parameters,
        )
        jdbc.query(
            """
            with candidate as (
                select id from $tableName
                where attempts < :max and ((state='PENDING' and next_attempt_at <= :now) or (state='RUNNING' and locked_until <= :now))
                order by next_attempt_at, id for update skip locked limit 1
            )
            update $tableName o set state='RUNNING', attempts=attempts+1, claim_token=:token, locked_until=:until, updated_at=:now
            from candidate c where o.id=c.id returning o.id, o.channel, o.payload::text, o.attempts, o.claim_token, o.trace_parent
            """.trimIndent(),
            parameters,
        ) { rs, _ ->
            DeliveryClaim(
                rs.getObject("id", UUID::class.java),
                rs.getString("channel"),
                rs.getString("payload"),
                rs.getInt("attempts"),
                rs.getObject("claim_token", UUID::class.java),
                rs.getString("trace_parent"),
            )
        }.firstOrNull()
    }

    fun complete(claim: DeliveryClaim, now: Instant): Boolean = finish(claim, now, "DELIVERED", now, null)

    fun retry(claim: DeliveryClaim, now: Instant, nextAttemptAt: Instant, maxAttempts: Int, errorCode: String): Boolean =
        finish(claim, now, if (claim.attempts >= maxAttempts) "FAILED" else "PENDING", nextAttemptAt, errorCode)

    private fun finish(claim: DeliveryClaim, now: Instant, state: String, nextAttemptAt: Instant, errorCode: String?): Boolean =
        jdbc.update(
            """
            update $tableName set state=:state, next_attempt_at=:next, updated_at=:now,
                delivered_at=case when :state='DELIVERED' then cast(:now as timestamptz) else null end,
                last_error_code=:error, claim_token=null, locked_until=null
            where id=:id and state='RUNNING' and claim_token=:token
            """.trimIndent(),
            mapOf(
                "id" to claim.id,
                "token" to claim.token,
                "state" to state,
                "next" to Timestamp.from(nextAttemptAt),
                "now" to Timestamp.from(now),
                "error" to errorCode,
            ),
        ) == 1
}
