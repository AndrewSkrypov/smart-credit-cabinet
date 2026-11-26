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
import java.time.Duration
import java.util.UUID

@Component
class CreditAgentNotificationAccess(
    @Value("${cabinet.credit.url:}") creditUrl: String,
    builder: RestClient.Builder,
) : AgentNotificationAccessPort {
    private val client = creditUrl.takeIf { it.isNotBlank() }?.let {
        val transport = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
        val factory = JdkClientHttpRequestFactory(transport).apply { setReadTimeout(Duration.ofSeconds(2)) }
        builder.clone().baseUrl(it).requestFactory(factory).build()
    }

    override fun check(applicationId: UUID, authorization: String): AgentNotificationAccess {
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
}
