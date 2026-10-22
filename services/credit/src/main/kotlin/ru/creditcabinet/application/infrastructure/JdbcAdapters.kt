package ru.creditcabinet.application.infrastructure

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import ru.creditcabinet.application.domain.Actor
import ru.creditcabinet.application.domain.ActorType
import ru.creditcabinet.application.domain.ApplicationJournal
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.IdempotencyKeys
import ru.creditcabinet.application.domain.JournalEntry
import ru.creditcabinet.application.domain.PipelineJob
import ru.creditcabinet.application.domain.PipelineJobs
import ru.creditcabinet.application.domain.PipelineStep
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Component
class JdbcApplicationJournal(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
) : ApplicationJournal {
    override fun append(entry: JournalEntry) {
        jdbc.update(
            """
            insert into application_events (id, application_id, event, from_status, to_status, actor_type, actor_id, reason_code, comment, payload, occurred_at)
            values (:id, :application, :event, :from, :to, :actorType, :actorId, :reason, :comment, cast(:payload as jsonb), :at)
            """.trimIndent(),
            mapOf(
                "id" to entry.id,
                "application" to entry.applicationId,
                "event" to entry.event,
                "from" to entry.fromStatus?.name,
                "to" to entry.toStatus.name,
                "actorType" to entry.actor.type.name,
                "actorId" to entry.actor.id,
                "reason" to entry.reasonCode,
                "comment" to entry.comment,
                "payload" to mapper.writeValueAsString(entry.payload),
                "at" to Timestamp.from(entry.occurredAt),
            ),
        )
    }

    override fun list(applicationId: UUID): List<JournalEntry> =
        jdbc.query(
            "select * from application_events where application_id = :application order by seq",
            mapOf("application" to applicationId),
        ) { rs, _ ->
            JournalEntry(
                id = rs.getObject("id", UUID::class.java),
                applicationId = rs.getObject("application_id", UUID::class.java),
                event = rs.getString("event"),
                fromStatus = rs.getString("from_status")?.let(ApplicationStatus::valueOf),
                toStatus = ApplicationStatus.valueOf(rs.getString("to_status")),
                actor = Actor(ActorType.valueOf(rs.getString("actor_type")), rs.getObject("actor_id", UUID::class.java)),
                reasonCode = rs.getString("reason_code"),
                comment = rs.getString("comment"),
                payload = mapper.readValue(rs.getString("payload"), PAYLOAD),
                occurredAt = rs.getTimestamp("occurred_at").toInstant(),
            )
        }

    private companion object {
        val PAYLOAD = object : TypeReference<Map<String, Any?>>() {}
    }
}

@Component
class JdbcPipelineJobs(private val jdbc: NamedParameterJdbcTemplate) : PipelineJobs {
    override fun enqueue(applicationId: UUID, step: PipelineStep, runAt: Instant) {
        val now = Timestamp.from(Instant.now())
        jdbc.update(
            """
            insert into pipeline_jobs (id, application_id, step, status, attempts, run_at, created_at, updated_at)
            values (:id, :application, :step, 'PENDING', 0, :runAt, :now, :now)
            on conflict (application_id, step) where status in ('PENDING', 'RUNNING') do nothing
            """.trimIndent(),
            mapOf("id" to UUID.randomUUID(), "application" to applicationId, "step" to step.name, "runAt" to Timestamp.from(runAt), "now" to now),
        )
    }

    override fun cancelPending(applicationId: UUID) {
        jdbc.update(
            """
            update pipeline_jobs set status = 'CANCELLED', locked_until = null, updated_at = now()
            where application_id = :application and status in ('PENDING', 'RUNNING')
            """.trimIndent(),
            mapOf("application" to applicationId),
        )
    }

    override fun claim(now: Instant, leaseUntil: Instant, limit: Int): List<PipelineJob> =
        jdbc.query(
            """
            update pipeline_jobs set status = 'RUNNING', locked_until = :leaseUntil, attempts = attempts + 1, updated_at = :now
            where id in (
                select id from pipeline_jobs
                where (status = 'PENDING' and run_at <= :now) or (status = 'RUNNING' and locked_until < :now)
                order by run_at
                limit :limit
                for update skip locked
            )
            returning id, application_id, step, attempts
            """.trimIndent(),
            mapOf("now" to Timestamp.from(now), "leaseUntil" to Timestamp.from(leaseUntil), "limit" to limit),
        ) { rs, _ ->
            PipelineJob(
                id = rs.getObject("id", UUID::class.java),
                applicationId = rs.getObject("application_id", UUID::class.java),
                step = PipelineStep.valueOf(rs.getString("step")),
                attempts = rs.getInt("attempts"),
            )
        }

    override fun complete(id: UUID) {
        jdbc.update(
            "update pipeline_jobs set status = 'DONE', locked_until = null, updated_at = now() where id = :id and status = 'RUNNING'",
            mapOf("id" to id),
        )
    }

    override fun retry(id: UUID, runAt: Instant, error: String?) {
        jdbc.update(
            """
            update pipeline_jobs set status = 'PENDING', run_at = :runAt, locked_until = null, last_error = :error, updated_at = now()
            where id = :id and status = 'RUNNING'
            """.trimIndent(),
            mapOf("id" to id, "runAt" to Timestamp.from(runAt), "error" to error?.take(2000)),
        )
    }

    override fun fail(id: UUID, error: String?) {
        jdbc.update(
            """
            update pipeline_jobs set status = 'FAILED', locked_until = null, last_error = :error, updated_at = now()
            where id = :id and status = 'RUNNING'
            """.trimIndent(),
            mapOf("id" to id, "error" to error?.take(2000)),
        )
    }
}

@Component
class JdbcIdempotencyKeys(private val jdbc: NamedParameterJdbcTemplate) : IdempotencyKeys {
    override fun find(userId: UUID, key: String, operation: String): UUID? =
        jdbc.query(
            "select application_id from idempotency_keys where user_id = :user and idempotency_key = :key and operation = :operation",
            mapOf("user" to userId, "key" to key, "operation" to operation),
        ) { rs, _ -> rs.getObject("application_id", UUID::class.java) }.firstOrNull()

    override fun record(userId: UUID, key: String, operation: String, applicationId: UUID) {
        jdbc.update(
            "insert into idempotency_keys (user_id, idempotency_key, operation, application_id) values (:user, :key, :operation, :application)",
            mapOf("user" to userId, "key" to key, "operation" to operation, "application" to applicationId),
        )
    }
}
