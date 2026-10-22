package ru.creditcabinet.observability

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@Transactional
class PipelineMetricsIntegrationTest {
    @Autowired private lateinit var metrics: PipelineMetrics

    @Autowired private lateinit var registry: MeterRegistry

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `database snapshots count committed shapes and measure the first final decision without replica multiplication`() {
        // Temporary tables isolate the aggregate fixture without editing the append-only production journal.
        jdbc.execute("create temporary table pipeline_jobs(status text, attempts integer, run_at timestamptz) on commit drop")
        jdbc.execute("create temporary table decisions(application_id uuid, outcome text, decided_by_type text, created_at timestamptz) on commit drop")
        jdbc.execute("create temporary table application_events(application_id uuid, event text, occurred_at timestamptz) on commit drop")
        jdbc.execute(
            """
            insert into pipeline_jobs values ('PENDING', 0, now() - interval '30 seconds'),
                ('PENDING', 0, now() + interval '1 hour'), ('FAILED', 3, now()), ('RUNNING', 7, now())
            """.trimIndent(),
        )
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        for (id in listOf(first, second)) {
            jdbc.update("insert into application_events values (?, 'SUBMIT', '2026-01-01T00:00:00Z')", id)
        }
        jdbc.update("insert into application_events values (?, 'SUBMIT', '2026-01-01T00:00:02Z')", first)
        jdbc.update("insert into decisions values (?, 'REQUEST_INFO', 'SYSTEM', '2026-01-01T00:00:01Z')", first)
        jdbc.update("insert into decisions values (?, 'APPROVE', 'SYSTEM', '2026-01-01T00:00:05Z')", first)
        jdbc.update("insert into decisions values (?, 'COUNTER_OFFER', 'UNDERWRITER', '2026-01-01T00:00:09Z')", first)
        jdbc.update("insert into decisions values (?, 'REJECT', 'UNDERWRITER', '2026-01-01T00:00:15Z')", second)
        metrics.refresh()
        assertEquals(2.0, registry.get("cabinet.pipeline.jobs").tags("status", "PENDING", "attempts", "0").gauge().value())
        assertEquals(1.0, registry.get("cabinet.pipeline.jobs").tags("status", "FAILED", "attempts", "2-4").gauge().value())
        assertEquals(1.0, registry.get("cabinet.pipeline.jobs").tags("status", "RUNNING", "attempts", "5+").gauge().value())
        assertEquals(0.0, registry.get("cabinet.pipeline.jobs").tags("status", "DONE", "attempts", "0").gauge().value())
        assertEquals(1.0, registry.get("cabinet.decisions").tags("outcome", "REQUEST_INFO", "actor", "SYSTEM").gauge().value())
        assertEquals(10.0, registry.get("cabinet.decision.time.average").gauge().value())
        assertEquals(15.0, registry.get("cabinet.decision.time.maximum").gauge().value())
        assertTrue(registry.get("cabinet.pipeline.queue.oldest.due").gauge().value() in 29.0..32.0)
        jdbc.execute("truncate decisions")
        metrics.refresh()
        assertTrue(registry.get("cabinet.decision.time.average").gauge().value().isNaN())
        assertTrue(registry.meters.filter { it.id.name.startsWith("cabinet.") }.flatMap { it.id.tags }.none { it.value.contains(first.toString()) })
    }
}
