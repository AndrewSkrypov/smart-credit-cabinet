package ru.creditcabinet.observability

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.creditcabinet.application.domain.PipelineStep
import ru.creditcabinet.assessment.domain.DecisionOutcome
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

@Component
class PipelineMetrics(private val registry: MeterRegistry, dataSource: DataSource, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbc = JdbcTemplate(dataSource).apply { queryTimeout = 3 }

    private data class Snapshot(
        val jobs: Map<Pair<String, String>, Double>,
        val decisions: Map<Pair<String, String>, Double>,
        val averageDecisionSeconds: Double,
        val maximumDecisionSeconds: Double,
        val oldestDueSeconds: Double,
        val at: Instant,
    )

    @Volatile private var snapshot: Snapshot? = null

    init {
        for (status in listOf("PENDING", "RUNNING", "DONE", "FAILED", "CANCELLED")) {
            for (attempts in listOf("0", "1", "2-4", "5+")) {
                Gauge.builder("cabinet.pipeline.jobs", this) { it.snapshot?.jobs?.get(status to attempts) ?: it.missingOrZero() }
                    .tags("status", status, "attempts", attempts).register(registry)
            }
        }
        for (outcome in DecisionOutcome.entries) {
            for (actor in listOf("SYSTEM", "UNDERWRITER")) {
                Gauge.builder("cabinet.decisions", this) { it.snapshot?.decisions?.get(outcome.name to actor) ?: it.missingOrZero() }
                    .tags("outcome", outcome.name, "actor", actor).register(registry)
            }
        }
        Gauge.builder("cabinet.decision.time.average", this) { it.snapshot?.averageDecisionSeconds ?: Double.NaN }
            .baseUnit("seconds").register(registry)
        Gauge.builder("cabinet.decision.time.maximum", this) { it.snapshot?.maximumDecisionSeconds ?: Double.NaN }
            .baseUnit("seconds").register(registry)
        Gauge.builder("cabinet.pipeline.queue.oldest.due", this) { it.snapshot?.oldestDueSeconds ?: Double.NaN }
            .baseUnit("seconds").register(registry)
        Gauge.builder("cabinet.pipeline.metrics.snapshot.age", this) {
            it.snapshot?.let { value -> Duration.between(value.at, clock.instant()).toMillis().coerceAtLeast(0) / 1000.0 } ?: Double.NaN
        }.baseUnit("seconds").register(registry)
    }

    private fun missingOrZero(): Double = if (snapshot == null) Double.NaN else 0.0

    fun step(step: PipelineStep, startedNanos: Long, failed: Boolean) {
        timer("cabinet.pipeline.step", "step", step.name, if (failed) "exception" else "completed")
            .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS)
    }

    fun source(source: String, result: String, startedNanos: Long) {
        timer("cabinet.source.call", "source", sourceLabel(source), result)
            .record(System.nanoTime() - startedNanos, TimeUnit.NANOSECONDS)
    }

    fun monitor(source: String, breaker: CircuitBreaker) {
        for (state in CircuitBreaker.State.entries) {
            Gauge.builder("cabinet.source.breaker", breaker) { if (it.state == state) 1.0 else 0.0 }
                .tags("source", sourceLabel(source), "state", state.name).register(registry)
        }
    }

    private fun timer(name: String, key: String, value: String, result: String): Timer =
        Timer.builder(name).tags(key, value, "result", result).publishPercentileHistogram().register(registry)

    private fun sourceLabel(source: String): String =
        when (source) {
            "ЕГРЮЛ/ЕГРИП" -> "registry"
            "ФНС" -> "tax"
            "БКИ" -> "bureau"
            "Данные банка" -> "bank"
            "Подписание" -> "signing"
            "АБС" -> "core"
            else -> "other"
        }

    // All replicas observe one database; dashboards must take max, not sum, for these snapshots.
    @Scheduled(fixedDelayString = "\${cabinet.metrics.refresh-interval:PT15S}")
    fun refresh() {
        try {
            val jobs = jdbc.query(
                """
                select status, case when attempts = 0 then '0' when attempts = 1 then '1'
                    when attempts < 5 then '2-4' else '5+' end as bucket, count(*) as total
                from pipeline_jobs group by status, bucket
                """.trimIndent(),
            ) { rs, _ -> (rs.getString("status") to rs.getString("bucket")) to rs.getDouble("total") }.toMap()
            val decisions = jdbc.query("select outcome, decided_by_type, count(*) as total from decisions group by outcome, decided_by_type") { rs, _ ->
                (rs.getString("outcome") to rs.getString("decided_by_type")) to rs.getDouble("total")
            }.toMap()
            val latency = jdbc.queryForMap(
                """
                select avg(extract(epoch from (d.at - s.at))) as average,
                    max(extract(epoch from (d.at - s.at))) as maximum
                from (select application_id, min(created_at) as at from decisions
                    where outcome in ('APPROVE', 'COUNTER_OFFER', 'REJECT') group by application_id) d
                join (select application_id, min(occurred_at) as at from application_events
                    where event = 'SUBMIT' group by application_id) s using (application_id)
                """.trimIndent(),
            )
            val oldest = jdbc.queryForObject(
                "select coalesce(greatest(extract(epoch from (now() - min(run_at))), 0), 0) from pipeline_jobs where status = 'PENDING' and run_at <= now()",
                Double::class.java,
            )!!
            snapshot = Snapshot(
                jobs,
                decisions,
                (latency["average"] as? Number)?.toDouble() ?: Double.NaN,
                (latency["maximum"] as? Number)?.toDouble() ?: Double.NaN,
                oldest,
                clock.instant(),
            )
        } catch (exception: Exception) {
            // An unavailable monitoring query must not change credit processing or erase the last known snapshot.
            registry.counter("cabinet.pipeline.metrics.snapshot.errors").increment()
            log.warn("Pipeline metrics snapshot failed: {}", exception.javaClass.simpleName)
        }
    }
}
