package ru.creditcabinet.observability

import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import javax.sql.DataSource

/** Each credit replica observes the same database. Dashboards deduplicate gauges with max by (stage). */
@Component
class AgentChannelMetrics(private val registry: MeterRegistry, source: DataSource) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val jdbc = JdbcTemplate(source).apply { queryTimeout = 3 }

    @Volatile private var reached: Map<String, Double>? = null

    init {
        for (stage in STAGES) {
            Gauge.builder("cabinet.agent.applications.reached", this) { metrics -> metrics.reached?.get(stage) ?: metrics.empty() }
                .tag("stage", stage).register(registry)
        }
    }

    @Scheduled(fixedDelayString = "\${cabinet.metrics.refresh-interval:PT15S}")
    fun refresh() {
        try {
            reached = jdbc.query(
                """
                select e.to_status, count(distinct e.application_id) as total
                from application_events e join applications a on a.id = e.application_id
                where a.agent_id is not null group by e.to_status
                """.trimIndent(),
            ) { row, _ -> row.getString("to_status") to row.getDouble("total") }.toMap()
        } catch (exception: Exception) {
            registry.counter("cabinet.agent.metrics.snapshot.errors").increment()
            log.warn("Agent funnel snapshot failed: {}", exception.javaClass.simpleName)
        }
    }

    private fun empty(): Double = if (reached == null) Double.NaN else 0.0

    private companion object {
        val STAGES =
            listOf("DRAFT", "PENDING_CLIENT_CONFIRMATION", "SUBMITTED", "INFO_REQUESTED", "MANUAL_REVIEW", "OFFER_ISSUED", "SIGNING", "DISBURSED", "REJECTED")
    }
}
