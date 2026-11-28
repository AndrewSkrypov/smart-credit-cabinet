package ru.creditcabinet.auth.infrastructure

import org.springframework.stereotype.Component
import ru.creditcabinet.auth.application.SignInLimits
import ru.creditcabinet.common.limits.LimitWindow
import ru.creditcabinet.common.limits.SharedLimitExceeded
import ru.creditcabinet.common.limits.SharedLimits
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Stops password guessing against one account: after [MAX_FAILURES] wrong passwords within
 * [WINDOW] the account answers 429 until the oldest attempt leaves the window. Shared mode
 * reserves before password verification, so concurrent guesses cannot race check/record.
 * The local implementation is for isolated tests/development without Valkey only.
 */
@Component
class SignInThrottle(private val shared: SharedLimits, private val clock: Clock = Clock.systemUTC()) : SignInLimits {
    private val failures = ConcurrentHashMap<String, List<Instant>>()

    override fun check(email: String, address: String) {
        if (shared.enabled) {
            shared.acquire(
                "Слишком много попыток входа. Попробуйте через несколько минут.",
                LimitWindow("signin", email, MAX_FAILURES, WINDOW),
                LimitWindow("signin-ip", address, MAX_ATTEMPTS_PER_ADDRESS, Duration.ofMinutes(1)),
            )
            return
        }
        val recent = recent(email)
        if (recent.size >= MAX_FAILURES) {
            val seconds = Duration.between(clock.instant(), recent.first().plus(WINDOW)).seconds + 1
            throw SharedLimitExceeded("Слишком много попыток входа. Попробуйте через ${(seconds + 59) / 60} мин.", seconds, "signin")
        }
    }

    override fun recordFailure(email: String) {
        if (shared.enabled) return // Already reserved atomically before checking the password.
        if (failures.size > MAX_TRACKED) evictExpired()
        failures.compute(email) { _, _ -> recent(email) + clock.instant() }
    }

    override fun reset(email: String) {
        if (shared.enabled) {
            shared.reset("signin", email)
            return
        }
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
        private const val MAX_ATTEMPTS_PER_ADDRESS = 120
        private const val MAX_TRACKED = 10_000
    }
}
