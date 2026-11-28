package ru.creditcabinet.document.infrastructure

import org.springframework.stereotype.Component
import ru.creditcabinet.application.application.CreditFailure.RateLimited
import ru.creditcabinet.application.infrastructure.creditPortCall
import ru.creditcabinet.common.limits.LimitWindow
import ru.creditcabinet.common.limits.SharedLimits
import ru.creditcabinet.document.application.DocumentUploadLimit
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Uploads per person: at most [MAX_UPLOADS] in [WINDOW], so one account cannot fill the
 * storage. Valkey counts all replicas; local state is only for isolated development/tests.
 */
@Component
class UploadThrottle(private val shared: SharedLimits, private val clock: Clock = Clock.systemUTC()) : DocumentUploadLimit {
    private val uploads = ConcurrentHashMap<UUID, List<Instant>>()

    override fun acquire(userId: UUID) {
        if (shared.enabled) {
            creditPortCall {
                shared.acquire(
                    "Слишком много загрузок подряд. Попробуйте через несколько минут.",
                    LimitWindow("upload", userId.toString(), MAX_UPLOADS, WINDOW),
                )
            }
            return
        }
        val now = clock.instant()
        val since = now.minus(WINDOW)
        if (uploads.size > MAX_TRACKED) uploads.keys.forEach { key -> if (uploads[key].orEmpty().none { it.isAfter(since) }) uploads.remove(key) }
        var allowed = true
        uploads.compute(userId) { _, previous ->
            val recent = previous.orEmpty().filter { it.isAfter(since) }
            if (recent.size >= MAX_UPLOADS) {
                allowed = false
                recent
            } else {
                recent + now
            }
        }
        if (!allowed) throw RateLimited("Слишком много загрузок подряд. Попробуйте через несколько минут.")
    }

    companion object {
        const val MAX_UPLOADS = 30
        val WINDOW: Duration = Duration.ofMinutes(10)
        private const val MAX_TRACKED = 10_000
    }
}
