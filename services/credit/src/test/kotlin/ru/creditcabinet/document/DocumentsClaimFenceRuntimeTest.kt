package ru.creditcabinet.document

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import ru.creditcabinet.application.domain.PipelineJob
import ru.creditcabinet.application.infrastructure.JdbcPipelineJobs
import ru.creditcabinet.observability.PipelineMetrics
import ru.creditcabinet.pipeline.PipelineSettings
import ru.creditcabinet.pipeline.application.PipelineJobHandler
import ru.creditcabinet.pipeline.infrastructure.PipelineWorker
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Required native/CI runtime gate; absent DB configuration fails rather than skipping. */
class DocumentsClaimFenceRuntimeTest {
    @Test
    fun `late generic exception cannot retry or fail a new live claim or change an expired claim`() {
        val source =
            DriverManagerDataSource(env("DOCUMENTS_TEST_DATABASE_BASE") + "/credit", env("DOCUMENTS_TEST_ADMIN_USER"), env("DOCUMENTS_TEST_ADMIN_PASSWORD"))
        val jdbc = JdbcTemplate(source)
        val jobs = JdbcPipelineJobs(NamedParameterJdbcTemplate(source))
        val clock = Clock.fixed(Instant.now(), ZoneOffset.UTC)
        for (maxAttempts in listOf(1, 3)) {
            val id = UUID.randomUUID()
            val application = UUID.fromString(env("DOCUMENTS_TEST_APPLICATION"))
            jdbc.update(
                "insert into pipeline_jobs(id,application_id,step,status,attempts,run_at,created_at,updated_at) values(?,?,'DATA_CHECK','PENDING',0,?,?,?)",
                id,
                application,
                Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()),
                Timestamp.from(clock.instant()),
            )
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            var old: PipelineJob? = null
            val handler = PipelineJobHandler { job ->
                old = job
                entered.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                throw IllegalStateException("Synthetic late Core HTTP400")
            }
            val worker =
                PipelineWorker(jobs, handler, PipelineSettings(maxAttempts = maxAttempts), clock, PipelineMetrics(SimpleMeterRegistry(), source, clock))
            val executor = Executors.newSingleThreadExecutor()
            try {
                val running = executor.submit<Int> { worker.runDue() }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                jdbc.update("update pipeline_jobs set locked_until=? where id=?", Timestamp.from(clock.instant().minusSeconds(1)), id)
                val fresh = jobs.claim(clock.instant(), clock.instant().plusSeconds(300), 1).single { it.id == id }
                release.countDown()
                assertEquals(1, running.get(10, TimeUnit.SECONDS))
                jobs.retryDocumentsUnavailable(old!!, clock.instant())
                assertCurrent(jdbc, fresh)
                // Exact lease matters even when attempt values happen to match after an outage retry.
                val sameAttemptOldLease = fresh.copy(leaseUntil = fresh.leaseUntil!!.minusSeconds(1))
                jobs.retryClaim(sameAttemptOldLease, clock.instant(), clock.instant(), "stale")
                jobs.failClaim(sameAttemptOldLease, clock.instant(), "stale")
                jobs.retryDocumentsUnavailable(sameAttemptOldLease, clock.instant())
                assertCurrent(jdbc, fresh)
                val expired = fresh.leaseUntil!!.plusSeconds(1)
                jobs.retryClaim(fresh, expired, expired, "expired")
                jobs.failClaim(fresh, expired, "expired")
                jobs.retryDocumentsUnavailable(fresh, expired)
                assertCurrent(jdbc, fresh)
                // An actual current callback still works; fenced APIs do not disable recovery.
                jobs.retryClaim(fresh, clock.instant(), clock.instant().plusSeconds(10), "current")
                assertEquals("PENDING", jdbc.queryForObject("select status from pipeline_jobs where id=?", String::class.java, id))
            } finally {
                release.countDown()
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
                jdbc.update("update pipeline_jobs set status='CANCELLED',locked_until=null where id=?", id)
            }
        }
    }

    private fun assertCurrent(jdbc: JdbcTemplate, job: PipelineJob) {
        val row = jdbc.queryForMap("select status,attempts,locked_until,documents_outages,last_error from pipeline_jobs where id=?", job.id)
        assertEquals("RUNNING", row["status"])
        assertEquals(job.attempts, row["attempts"])
        assertEquals(job.leaseUntil, (row["locked_until"] as Timestamp).toInstant())
        assertEquals(0, row["documents_outages"])
        assertEquals(null, row["last_error"])
    }
    private fun env(key: String): String = System.getenv(key)?.takeIf { it.isNotBlank() } ?: error("Required runtime configuration: $key")
}
