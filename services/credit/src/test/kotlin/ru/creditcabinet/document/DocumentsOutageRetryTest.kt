package ru.creditcabinet.document

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import ru.creditcabinet.application.domain.PipelineJob
import ru.creditcabinet.application.domain.PipelineJobs
import ru.creditcabinet.application.domain.PipelineStep
import ru.creditcabinet.document.application.DocumentsUnavailable
import ru.creditcabinet.observability.PipelineMetrics
import ru.creditcabinet.pipeline.PipelineSettings
import ru.creditcabinet.pipeline.application.PipelineJobHandler
import ru.creditcabinet.pipeline.infrastructure.PipelineWorker
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentsOutageRetryTest {
    @Test
    fun `Documents outage longer than business attempt limit recovers without failing DATA_CHECK`() {
        val jobs = mock<PipelineJobs>()
        val handler = mock<PipelineJobHandler>()
        val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)
        val job = PipelineJob(UUID.randomUUID(), UUID.randomUUID(), PipelineStep.DATA_CHECK, 1, clock.instant().plusSeconds(120))
        val settings = PipelineSettings(maxAttempts = 2)
        var outage = true
        doAnswer { if (outage) throw DocumentsUnavailable() else null }.whenever(handler).run(job)
        val worker = PipelineWorker(jobs, handler, settings, clock, PipelineMetrics(SimpleMeterRegistry(), mock<DataSource>(), clock))
        repeat(settings.maxAttempts + 3) {
            whenever(jobs.claim(any(), any(), any())).thenReturn(listOf(job), emptyList())
            assertEquals(1, worker.runDue())
        }
        outage = false
        whenever(jobs.claim(any(), any(), any())).thenReturn(listOf(job), emptyList())
        assertEquals(1, worker.runDue())
        verify(jobs, times(5)).retryDocumentsUnavailable(job, clock.instant())
        verify(jobs, never()).fail(any(), any())
        verify(jobs, never()).retry(any(), any(), any())
        verify(handler, times(6)).run(job)
    }
}
