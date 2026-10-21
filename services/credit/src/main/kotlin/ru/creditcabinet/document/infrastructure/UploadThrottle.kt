package ru.creditcabinet.document.infrastructure

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import ru.creditcabinet.common.exception.ApiException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Uploads per person: at most [MAX_UPLOADS] in [WINDOW], so one account cannot fill the
 * storage. Counted per instance, like the sign-in limit in identity.
 */
@Component
class UploadThrottle(private val clock: Clock = Clock.systemUTC()) {
    private val uploads = ConcurrentHashMap<UUID, List<Instant>>()

    fun acquire(userId: UUID) {
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
        if (!allowed) throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "Слишком много загрузок подряд. Попробуйте через несколько минут.")
    }

    companion object {
        const val MAX_UPLOADS = 30
        val WINDOW: Duration = Duration.ofMinutes(10)
        private const val MAX_TRACKED = 10_000
    }
}
