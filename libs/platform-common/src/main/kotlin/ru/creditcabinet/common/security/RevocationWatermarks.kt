package ru.creditcabinet.common.security

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import ru.creditcabinet.common.limits.ValkeyState
import java.util.UUID

/** Only a fast DENY cache. An equal/missing watermark never authorizes a token. */
@Component
class RevocationWatermarks(private val state: ValkeyState, private val metrics: MeterRegistry) {
    fun isOlder(userId: UUID, version: Int): Boolean {
        if (!state.enabled) return false
        return try {
            val known = state.read(state.key("credentials", userId.toString()))?.toIntOrNull()
            (known != null && version < known).also {
                metrics.counter("cabinet.revocation_cache", "outcome", if (it) "denied" else "authority_required").increment()
            }
        } catch (_: Exception) {
            metrics.counter("cabinet.revocation_cache", "outcome", "read_unavailable").increment()
            false // The caller MUST still read identity; outage cannot permit an old token.
        }
    }

    fun afterCommit(userId: UUID, version: Int) {
        if (!state.enabled) return
        require(version >= 0)
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            check(TransactionSynchronizationManager.isSynchronizationActive())
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = observe(userId, version)
            })
        } else {
            observe(userId, version)
        }
    }

    fun observe(userId: UUID, version: Int) {
        if (!state.enabled) return
        require(version >= 0)
        try {
            state.eval(MAXIMUM, listOf(state.key("credentials", userId.toString())), listOf(version.toString()))
            metrics.counter("cabinet.revocation_cache", "outcome", "published").increment()
        } catch (_: Exception) {
            // A committed password/access change remains committed. Every service still checks
            // authoritative identity before ALLOW, even if this best-effort publication failed.
            metrics.counter("cabinet.revocation_cache", "outcome", "write_unavailable").increment()
        }
    }

    private companion object {
        val MAXIMUM = """
            local previous = tonumber(redis.call('GET', KEYS[1]) or '-1')
            local next = tonumber(ARGV[1])
            if next > previous then redis.call('SET', KEYS[1], ARGV[1], 'EX', 604800) end
            return math.max(previous, next)
        """.trimIndent()
    }
}
