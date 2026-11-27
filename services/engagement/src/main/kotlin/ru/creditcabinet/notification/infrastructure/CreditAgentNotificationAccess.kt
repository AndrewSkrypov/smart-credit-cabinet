package ru.creditcabinet.notification.infrastructure

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import ru.creditcabinet.notification.application.AgentNotificationAccess
import ru.creditcabinet.notification.application.AgentNotificationAccessPort
import java.net.http.HttpClient
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

@Component
class CreditAgentNotificationAccess(
    @Value("\${cabinet.credit.url:}") creditUrl: String,
    builder: RestClient.Builder,
    private val clock: Clock = Clock.systemUTC(),
) : AgentNotificationAccessPort {
    private val client = creditUrl.takeIf { it.isNotBlank() }?.let {
        val transport = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val factory = JdkClientHttpRequestFactory(transport).apply { setReadTimeout(Duration.ofSeconds(2)) }
        builder.clone().baseUrl(it).requestFactory(factory).build()
    }

    /** The unread badge is polled; a short memory per token keeps it from re-asking credit every few seconds. */
    private val recent = ConcurrentHashMap<Pair<String, UUID>, Pair<AgentNotificationAccess, Instant>>()

    override fun check(applicationId: UUID, authorization: String): AgentNotificationAccess {
        val key = fingerprint(authorization) to applicationId
        val now = clock.instant()
        recent[key]?.takeIf { it.second.isAfter(now) }?.let { return it.first }
        val result = ask(applicationId, authorization)
        if (result != AgentNotificationAccess.UNAVAILABLE) {
            if (recent.size > MAX_REMEMBERED) recent.entries.removeIf { !it.value.second.isAfter(now) }
            recent[key] = result to now.plus(REMEMBER_FOR)
        }
        return result
    }

    private fun ask(applicationId: UUID, authorization: String): AgentNotificationAccess {
        val target = client ?: return AgentNotificationAccess.UNAVAILABLE
        return try {
            target.get().uri("/api/v1/agent/applications/{id}/notification-access", applicationId)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .exchange { _, response ->
                    when (response.statusCode) {
                        HttpStatus.NO_CONTENT -> AgentNotificationAccess.ALLOWED
                        HttpStatus.NOT_FOUND -> AgentNotificationAccess.DENIED
                        else -> AgentNotificationAccess.UNAVAILABLE
                    }
                } ?: AgentNotificationAccess.UNAVAILABLE
        } catch (_: RestClientException) {
            AgentNotificationAccess.UNAVAILABLE
        }
    }

    private fun fingerprint(authorization: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(authorization.toByteArray()))

    private companion object {
        val REMEMBER_FOR: Duration = Duration.ofSeconds(15)
        const val MAX_REMEMBERED = 2_000
    }
}
