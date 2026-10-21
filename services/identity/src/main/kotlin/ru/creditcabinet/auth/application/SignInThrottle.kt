package ru.creditcabinet.auth.application

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import ru.creditcabinet.common.exception.ApiException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Stops password guessing against one account: after [MAX_FAILURES] wrong passwords within
 * [WINDOW] the account answers 429 until the oldest failure leaves the window. Counted per
 * instance (a guesser gets at most MAX_FAILURES x replicas tries); requests per client address to
 * the whole identity API are limited at the gateway (docker/gateway/nginx.conf).
 */
@Component
class SignInThrottle(private val clock: Clock = Clock.systemUTC()) {
    private val failures = ConcurrentHashMap<String, List<Instant>>()

    fun check(email: String) {
        val recent = recent(email)
        if (recent.size >= MAX_FAILURES) {
            val minutes = Duration.between(clock.instant(), recent.first().plus(WINDOW)).toMinutes() + 1
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "Слишком много попыток входа. Попробуйте через $minutes мин.")
        }
    }

    fun recordFailure(email: String) {
        if (failures.size > MAX_TRACKED) evictExpired()
        failures.compute(email) { _, _ -> recent(email) + clock.instant() }
    }

    fun reset(email: String) {
        failures.remove(email)
    }

    private fun recent(email: String): List<Instant> {
        val since = clock.instant().minus(WINDOW)
        return failures[email].orEmpty().filter { it.isAfter(since) }
    }

    private fun evictExpired() {
        failures.keys.forEach { key -> if (recent(key).isEmpty()) failures.remove(key) }
    }

    companion object {
        const val MAX_FAILURES = 5
        val WINDOW: Duration = Duration.ofMinutes(15)
        private const val MAX_TRACKED = 10_000
    }
}
