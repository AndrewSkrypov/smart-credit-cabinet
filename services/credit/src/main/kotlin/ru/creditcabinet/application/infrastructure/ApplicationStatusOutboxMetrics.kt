package ru.creditcabinet.application.infrastructure

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import ru.creditcabinet.common.outbox.DeliveryObservation
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

class ApplicationStatusOutboxMetrics(
    private val registry: MeterRegistry,
    dataSource: DataSource,
    private val topic: String,
) : DeliveryObservation {
    private val jdbc = JdbcTemplate(dataSource).apply { queryTimeout = 3 }
    private data class Backlog(val messages: Double, val oldestSeconds: Double)

    @Volatile private var snapshot: Map<String, Backlog>? = null

    init {
        for (state in listOf("PENDING", "RUNNING", "FAILED")) {
            Gauge.builder("cabinet.application.status.outbox.messages", this) { it.messages(state) }
                .tag("state", state).register(registry)
        }
        Gauge.builder("cabinet.application.status.outbox.oldest", this) { it.oldest() }
            .baseUnit("seconds").register(registry)
    }

    @Scheduled(fixedDelayString = "\${cabinet.application-status-events.metrics-interval:PT5S}")
    fun refresh() {
        try {
            snapshot = jdbc.query(
                """
                select state, count(*) as messages, greatest(0, extract(epoch from now() - min(created_at))) as oldest_seconds
                from application_status_outbox
                where stream_generation = ? and state in ('PENDING', 'RUNNING', 'FAILED')
                group by state
                """.trimIndent(),
                { rs, _ -> rs.getString("state") to Backlog(rs.getDouble("messages"), rs.getDouble("oldest_seconds")) },
                topic,
            ).toMap()
        } catch (_: Exception) {
            snapshot = null
            registry.counter("cabinet.application.status.outbox.snapshot.errors").increment()
        }
    }

    override fun record(channel: String, outcome: String, elapsedNanos: Long) {
        registry.timer("cabinet.application.status.outbox.attempt", "outcome", outcome).record(elapsedNanos, TimeUnit.NANOSECONDS)
    }

    private fun messages(state: String): Double = snapshot?.let { it[state]?.messages ?: 0.0 } ?: Double.NaN

    private fun oldest(): Double = snapshot?.let { it.values.maxOfOrNull(Backlog::oldestSeconds) ?: 0.0 } ?: Double.NaN
}
