package ru.creditcabinet.common.outbox

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

fun interface DeliveryObservation {
    fun record(channel: String, outcome: String, elapsedNanos: Long)
}

class OutboxMetrics(private val registry: MeterRegistry, dataSource: DataSource) : DeliveryObservation {
    private val jdbc = JdbcTemplate(dataSource).apply { queryTimeout = 3 }
    private data class Snapshot(val counts: Map<Pair<String, String>, Double>, val ages: Map<String, Double>, val at: Long)

    @Volatile private var snapshot: Snapshot? = null

    init {
        for (channel in CHANNELS) {
            for (state in listOf("PENDING", "RUNNING", "DELIVERED", "FAILED")) {
                Gauge.builder("cabinet.delivery.outbox.messages", this) { it.count(channel, state) }
                    .tags("channel", channel, "state", state).register(registry)
            }
            Gauge.builder("cabinet.delivery.outbox.oldest", this) { it.snapshot?.ages?.get(channel) ?: it.zeroOrMissing() }
                .tag("channel", channel).baseUnit("seconds").register(registry)
        }
        Gauge.builder("cabinet.delivery.snapshot.age", this) {
            it.snapshot?.let { value -> (System.nanoTime() - value.at).toDouble() / 1_000_000_000 } ?: Double.NaN
        }.baseUnit("seconds").register(registry)
    }

    @Scheduled(fixedDelayString = "\${cabinet.outbox.metrics-interval:PT15S}")
    fun refresh() {
        try {
            val counts = jdbc.query(
                """
                select case when channel in ('notification','email-notification','email-security') then channel else 'unknown' end as channel,
                    state,count(*) as n from delivery_outbox group by 1,2
                """.trimIndent(),
            ) { rs, _ ->
                (rs.getString("channel") to rs.getString("state")) to rs.getDouble("n")
            }.toMap()
            val ages = jdbc.query(
                """
                select case when channel in ('notification','email-notification','email-security') then channel else 'unknown' end as channel,
                    greatest(0,extract(epoch from now()-min(created_at))) as age
                from delivery_outbox where state in ('PENDING','RUNNING') group by 1
                """.trimIndent(),
            ) { rs, _ -> rs.getString("channel") to rs.getDouble("age") }.toMap()
            snapshot = Snapshot(counts, ages, System.nanoTime())
        } catch (_: Exception) {
            registry.counter("cabinet.delivery.snapshot.errors").increment()
        }
    }

    override fun record(channel: String, outcome: String, elapsedNanos: Long) {
        val boundedChannel = channel.takeIf { it in CHANNELS } ?: "unknown"
        val boundedOutcome = outcome.takeIf { it in setOf("DELIVERED", "RETRY", "FAILED", "STALE") } ?: "UNKNOWN"
        Timer.builder("cabinet.delivery.attempt").tags("channel", boundedChannel, "outcome", boundedOutcome)
            .register(registry).record(elapsedNanos, TimeUnit.NANOSECONDS)
    }

    private fun count(channel: String, state: String): Double = snapshot?.counts?.get(channel to state) ?: zeroOrMissing()
    private fun zeroOrMissing(): Double = if (snapshot == null) Double.NaN else 0.0

    private companion object {
        val CHANNELS = setOf("notification", "email-notification", "email-security", "unknown")
    }
}
