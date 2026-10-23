package ru.creditcabinet.common.limits

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import ru.creditcabinet.common.exception.ApiException
import java.time.Duration
import java.util.UUID

data class LimitWindow(val scope: String, val subject: String, val maximum: Int, val window: Duration) {
    init {
        require(scope.matches(Regex("[a-z-]{1,32}")))
        require(maximum in 1..1000 && window.toMillis() in 1..86_400_000)
    }
}

class SharedLimitExceeded(message: String, val retryAfterSeconds: Long) : ApiException(HttpStatus.TOO_MANY_REQUESTS, message)

/** One server-clock rolling window shared by every replica. No partial multi-key acquisition. */
@Component
class SharedLimits(private val state: ValkeyState, private val metrics: MeterRegistry) {
    val enabled: Boolean get() = state.enabled

    fun acquire(message: String, vararg windows: LimitWindow) {
        check(enabled)
        require(windows.isNotEmpty() && windows.size <= 4)
        val keys = windows.map { state.key("limit-${it.scope}", it.subject) }
        require(keys.distinct().size == keys.size)
        val result = try {
            state.eval(
                ACQUIRE,
                keys,
                listOf(UUID.randomUUID().toString()) + windows.flatMap {
                    listOf(it.maximum.toString(), it.window.toMillis().toString())
                },
            ) as List<*>
        } catch (_: Exception) {
            metrics.counter("cabinet.shared_limits", "operation", windows.first().scope, "outcome", "unavailable").increment()
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Проверка лимита временно недоступна — попробуйте позже")
        }
        val allowed = (result[0] as Number).toLong() == 1L
        metrics.counter("cabinet.shared_limits", "operation", windows.first().scope, "outcome", if (allowed) "allowed" else "limited").increment()
        if (!allowed) throw SharedLimitExceeded(message, ((result[1] as Number).toLong() + 999) / 1000)
    }

    fun reset(scope: String, subject: String) {
        try {
            state.remove(state.key("limit-$scope", subject))
        } catch (_: Exception) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Проверка лимита временно недоступна — попробуйте позже")
        }
    }

    private companion object {
        // Check every quota before adding a member to any quota. Rejected requests do not extend
        // the ban, nor consume a different subject's remaining quota. Each ZSET stays <= maximum.
        val ACQUIRE = """
            local t = redis.call('TIME')
            local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
            local retry = 0
            for i, key in ipairs(KEYS) do
                local maximum = tonumber(ARGV[i * 2])
                local window = tonumber(ARGV[i * 2 + 1])
                redis.call('ZREMRANGEBYSCORE', key, '-inf', now - window)
                if redis.call('ZCARD', key) >= maximum then
                    local first = redis.call('ZRANGE', key, 0, 0, 'WITHSCORES')
                    retry = math.max(retry, tonumber(first[2]) + window - now)
                end
            end
            if retry > 0 then return {0, retry} end
            for i, key in ipairs(KEYS) do
                redis.call('ZADD', key, now, ARGV[1])
                redis.call('PEXPIRE', key, tonumber(ARGV[i * 2 + 1]))
            end
            return {1, 0}
        """.trimIndent()
    }
}
