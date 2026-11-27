package ru.creditcabinet.document.infrastructure

import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Independent durable deletion delivery: no Credit business transaction or shared outbox lock. */
@Component
class DocumentsDeletionDelivery(
    private val boundary: DocumentsBoundary,
    private val jdbc: JdbcTemplate,
    @Value("\${cabinet.documents.deletions.enabled:true}") private val enabled: Boolean,
) {
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "documents-deletions").apply { isDaemon = true } }

    @Scheduled(fixedDelayString = "\${cabinet.documents.deletions.poll-interval:PT1S}")
    fun poll() {
        if (!enabled || !running.compareAndSet(false, true)) return
        executor.execute {
            try {
                runCatching { deliver() }
            } finally {
                running.set(false)
            }
        }
    }
    fun deliver() {
        if (!boundary.remote) return
        val pending = jdbc.query(
            "select document_id,application_id,deleted_at from document_deletion_intents where delivered_at is null and run_at<=now() order by run_at limit 2",
            {
                    rs,
                    _,
                ->
                Triple(rs.getObject(1, UUID::class.java), rs.getObject(2, UUID::class.java), rs.getTimestamp(3).toInstant())
            },
        )
        pending.forEach { (id, application, at) ->
            try {
                val scope = boundary.scope(application)
                boundary.rpc {
                    val record = boundary.client().list(scope).single { it.id == id }
                    boundary.client().delete(scope, id, record.context, at)
                }
                jdbc.update("update document_deletion_intents set delivered_at=now() where document_id=? and delivered_at is null", id)
            } catch (_: Exception) {
                // No payload, object name, SQL parameters, token or credentials enter logs.
                jdbc.update(
                    "update document_deletion_intents set attempts=least(attempts+1,7),run_at=now()+least(300,5*power(2,least(attempts,6)))*interval '1 second' where document_id=? and delivered_at is null",
                    id,
                )
            }
        }
    }

    @PreDestroy fun close() {
        executor.shutdownNow()
    }
}
