package ru.creditcabinet.common.security

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import ru.creditcabinet.common.exception.UnauthorizedException
import java.time.Duration
import java.util.UUID
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
 * Reads identity's authoritative version for each authenticated request. A cached positive
 * answer cannot prove that a password or employee access has not changed since it was read.
 */
class IdentityCredentialsCheck(
    private val client: RestClient,
    private val now: () -> Long = System::nanoTime,
) : CredentialsCheck {
    private val lastWarning = AtomicLong(0)

    override fun currentVersion(userId: UUID): Int? {
        return try {
            val value = client.get().uri("/internal/v1/users/{id}/credentials", userId).retrieve().body(JsonNode::class.java)?.get("credentialsVersion")
            value?.takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()?.takeIf { it >= 0 }
        } catch (_: HttpClientErrorException.NotFound) {
            throw UnauthorizedException("Сессия больше не действует — войдите заново")
        } catch (exception: Exception) {
            warn(exception)
            null
        }
    }

    private fun warn(exception: Exception) {
        val at = now()
        val last = lastWarning.get()
        if (at - last > Duration.ofMinutes(1).toNanos() && lastWarning.compareAndSet(last, at)) {
            log.warn("Credentials check is unavailable ({})", exception.javaClass.simpleName)
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(IdentityCredentialsCheck::class.java)
    }
}
