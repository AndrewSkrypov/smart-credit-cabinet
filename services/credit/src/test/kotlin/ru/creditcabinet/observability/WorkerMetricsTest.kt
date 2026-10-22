package ru.creditcabinet.observability

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import ru.creditcabinet.application.domain.PipelineJob
import ru.creditcabinet.application.domain.PipelineJobs
import ru.creditcabinet.application.domain.PipelineStep
import ru.creditcabinet.pipeline.PipelineSettings
import ru.creditcabinet.pipeline.PipelineSteps
import ru.creditcabinet.pipeline.PipelineWorker
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WorkerMetricsTest {
    @Test
    fun `a failed worker attempt retains retry semantics and records one exception duration`() {
        val registry = SimpleMeterRegistry()
        val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        val metrics = PipelineMetrics(registry, mock<DataSource>(), clock)
        val jobs = mock<PipelineJobs>()
        val steps = mock<PipelineSteps>()
        val job = PipelineJob(UUID.randomUUID(), UUID.randomUUID(), PipelineStep.ENRICHMENT, 1)
        whenever(jobs.claim(any(), any(), any())).thenReturn(listOf(job), emptyList())
        doThrow(IllegalStateException("synthetic failure")).whenever(steps).run(job)
        val worker = PipelineWorker(jobs, steps, PipelineSettings(), clock, metrics)
        assertEquals(1, worker.runDue())
        verify(jobs).retry(job.id, clock.instant().plusSeconds(10), "synthetic failure")
        val timer = registry.get("cabinet.pipeline.step").tags("step", "ENRICHMENT", "result", "exception").timer()
        assertEquals(1L, timer.count())
        assertTrue(timer.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS) > 0)
    }

    @Test
    fun `an unavailable metrics database leaves unknown values and reports the failed snapshot`() {
        val registry = SimpleMeterRegistry()
        val source = mock<DataSource>()
        whenever(source.connection).thenThrow(java.sql.SQLException("synthetic unavailable"))
        val metrics = PipelineMetrics(registry, source, Clock.systemUTC())
        metrics.refresh()
        assertEquals(1.0, registry.get("cabinet.pipeline.metrics.snapshot.errors").counter().count())
        assertTrue(registry.get("cabinet.pipeline.metrics.snapshot.age").gauge().value().isNaN())
    }
}
