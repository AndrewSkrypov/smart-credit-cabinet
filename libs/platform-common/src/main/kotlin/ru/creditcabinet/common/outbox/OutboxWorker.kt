package ru.creditcabinet.common.outbox

import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ThreadLocalRandom

interface DeliveryHandler {
    val channel: String
    fun deliver(payload: String)
}

@ConfigurationProperties("cabinet.outbox")
data class OutboxSettings(
    val enabled: Boolean = false,
    val workerEnabled: Boolean = true,
    val lease: Duration = Duration.ofSeconds(30),
    val maxAttempts: Int = 12,
) {
    init {
        require(lease >= Duration.ofSeconds(10) && maxAttempts in 1..100) { "Invalid outbox lease or attempts" }
    }
}

class OutboxWorker(
    private val outbox: DurableOutbox,
    handlers: List<DeliveryHandler>,
    private val settings: OutboxSettings,
    private val clock: Clock = Clock.systemUTC(),
    private val observation: DeliveryObservation = DeliveryObservation { _, _, _ -> },
) {
    private val handlers = handlers.associateBy { it.channel }
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(this.handlers.size == handlers.size) { "An outbox channel has exactly one handler" }
    }

    @Scheduled(fixedDelayString = "\${cabinet.outbox.poll-interval:PT1S}", scheduler = "notificationDeliveryScheduler")
    fun poll() {
        if (settings.workerEnabled) runDue()
    }

    fun runDue(limit: Int = 20): Int {
        var processed = 0
        repeat(limit) {
            val claim = outbox.claim(clock.instant(), settings.lease, settings.maxAttempts) ?: return processed
            val started = System.nanoTime()
            try {
                val handler = handlers[claim.channel] ?: error("Outbox channel has no handler")
                handler.deliver(claim.payload)
                val current = outbox.complete(claim, clock.instant())
                observe(claim.channel, if (current) "DELIVERED" else "STALE", started)
            } catch (exception: Exception) {
                val seconds = (1L shl (claim.attempts - 1).coerceIn(0, 8)).coerceAtMost(300)
                val pause = Duration.ofSeconds(seconds).plusMillis(ThreadLocalRandom.current().nextLong(250))
                // Exception messages can contain links or user data; persist and log only a bounded error code.
                val code = exception.javaClass.simpleName.take(120)
                val current = outbox.retry(claim, clock.instant(), clock.instant().plus(pause), settings.maxAttempts, code)
                observe(
                    claim.channel,
                    if (!current) {
                        "STALE"
                    } else if (claim.attempts >= settings.maxAttempts) {
                        "FAILED"
                    } else {
                        "RETRY"
                    },
                    started,
                )
                log.warn("Delivery {} on channel {} failed at attempt {} ({})", claim.id, claim.channel, claim.attempts, code)
            }
            processed++
        }
        return processed
    }

    private fun observe(channel: String, outcome: String, started: Long) {
        runCatching { observation.record(channel, outcome, System.nanoTime() - started) }
    }
}
