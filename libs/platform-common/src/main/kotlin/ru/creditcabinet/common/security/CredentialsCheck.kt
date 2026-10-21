package ru.creditcabinet.common.security

import org.slf4j.LoggerFactory
import org.springframework.web.client.RestClient
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Tells whether a token was issued before the user's last password change or reset.
 * Signature and expiry prove a token is genuine, not that it is still wanted.
 */
fun interface CredentialsCheck {
    /** The current credentials version of [userId], or null when it cannot be told right now. */
    fun currentVersion(userId: UUID): Int?
}

/**
 * Asks identity (GET /internal/v1/users/{id}/credentials) and keeps each answer for [ttl]:
 * after a password change old tokens stop working everywhere within that time.
 *
 * While identity cannot be reached the last known answer is used, and without one the token is
 * accepted: an identity outage must not sign everybody out of the other services. Identity
 * itself checks against its database and is always strict.
 */
class IdentityCredentialsCheck(
    private val client: RestClient,
    private val ttl: Duration = Duration.ofSeconds(30),
    private val now: () -> Long = System::nanoTime,
) : CredentialsCheck {
    private data class Entry(val version: Int?, val fetchedAt: Long)

    private val cache = ConcurrentHashMap<UUID, Entry>()
    private val lastWarning = AtomicLong(0)

    override fun currentVersion(userId: UUID): Int? {
        val cached = cache[userId]
        if (cached != null && now() - cached.fetchedAt < ttl.toNanos()) return cached.version
        val fresh = try {
            client.get().uri("/internal/v1/users/{id}/credentials", userId).retrieve().body(CredentialsResponse::class.java)?.credentialsVersion
        } catch (exception: Exception) {
            warn(exception)
            // Remember the failure for a while too, so an outage does not add a call per request.
            cached?.version
        }
        cache[userId] = Entry(fresh, now())
        return fresh
    }

    private fun warn(exception: Exception) {
        val at = now()
        val last = lastWarning.get()
        if (at - last > Duration.ofMinutes(1).toNanos() && lastWarning.compareAndSet(last, at)) {
            log.warn("Credentials check: identity is unreachable ({}); tokens are checked against the last known versions", exception.message)
        }
    }

    data class CredentialsResponse(val credentialsVersion: Int = 0)

    private companion object {
        val log = LoggerFactory.getLogger(IdentityCredentialsCheck::class.java)
    }
}
