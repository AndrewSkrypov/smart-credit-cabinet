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
            "select * from application_events where application_id = :application order by occurred_at, id",
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
            """.trimIndent(),
            mapOf("id" to UUID.randomUUID(), "application" to applicationId, "step" to step.name, "runAt" to Timestamp.from(runAt), "now" to now),
        )
    }

    override fun cancelPending(applicationId: UUID) {
        jdbc.update(
            "update pipeline_jobs set status = 'CANCELLED', updated_at = now() where application_id = :application and status = 'PENDING'",
            mapOf("application" to applicationId),
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
