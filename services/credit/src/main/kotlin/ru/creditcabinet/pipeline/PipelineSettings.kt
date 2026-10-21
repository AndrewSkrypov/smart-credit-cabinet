package ru.creditcabinet.pipeline

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Timings of the pipeline (docs/process-model.md, section 8). Production values by default; the
 * demo in docker compose shortens them so a whole scenario fits into minutes.
 */
@ConfigurationProperties("cabinet.pipeline")
data class PipelineSettings(
    /** false: no background worker (tests run the jobs themselves). */
    val enabled: Boolean = true,
    val pollInterval: Duration = Duration.ofSeconds(1),
    /** How long a worker holds a job before another may take it over. */
    val lease: Duration = Duration.ofMinutes(2),
    val infoRequestTtl: Duration = Duration.ofDays(14),
    val offerTtl: Duration = Duration.ofDays(5),
    /** A source that has not answered for this long sends the application to an underwriter. */
    val sourceTimeout: Duration = Duration.ofHours(1),
    /** Pauses between attempts to ask a source; the last one repeats. */
    val sourceRetryDelays: List<Duration> = listOf(Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(5)),
    /** Attempts after an unexpected error before a job is marked FAILED for an operator. */
    val maxAttempts: Int = 20,
) {
    fun sourceRetryDelay(attempt: Int): Duration = sourceRetryDelays[(attempt - 1).coerceIn(0, sourceRetryDelays.lastIndex)]
}
