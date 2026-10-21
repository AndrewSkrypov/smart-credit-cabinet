package ru.creditcabinet.pipeline

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.creditcabinet.application.domain.PipelineJob
import ru.creditcabinet.application.domain.PipelineJobs
import java.time.Clock
import java.time.Duration

/**
 * Runs due pipeline jobs. Several instances can run side by side: a job is taken with FOR UPDATE
 * SKIP LOCKED and a lease, so each runs once, and the job of a crashed instance is taken over
 * when its lease ends. An unexpected error is retried with a growing pause and, after
 * [PipelineSettings.maxAttempts], left FAILED for an operator.
 */
@Component
@EnableScheduling
@EnableConfigurationProperties(PipelineSettings::class)
class PipelineWorker(
    private val jobs: PipelineJobs,
    private val steps: PipelineSteps,
    private val settings: PipelineSettings,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "\${cabinet.pipeline.poll-interval:PT1S}")
    fun poll() {
        if (settings.enabled) runDue()
    }

    /** Runs everything due now, including the steps the finished ones enqueue for now. Returns the number of jobs run. */
    fun runDue(): Int {
        var total = 0
        while (true) {
            val now = clock.instant()
            val batch = jobs.claim(now, now.plus(settings.lease), BATCH)
            if (batch.isEmpty()) return total
            batch.forEach(::process)
            total += batch.size
        }
    }

    private fun process(job: PipelineJob) {
        try {
            steps.run(job)
        } catch (exception: Exception) {
            log.error("Pipeline step {} of application {} failed (attempt {})", job.step, job.applicationId, job.attempts, exception)
            if (job.attempts >= settings.maxAttempts) {
                jobs.fail(job.id, exception.message)
            } else {
                val pause = Duration.ofSeconds(10).multipliedBy(1L shl (job.attempts - 1).coerceIn(0, 5))
                jobs.retry(job.id, clock.instant().plus(pause), exception.message)
            }
        }
    }

    private companion object {
        const val BATCH = 10
    }
}
