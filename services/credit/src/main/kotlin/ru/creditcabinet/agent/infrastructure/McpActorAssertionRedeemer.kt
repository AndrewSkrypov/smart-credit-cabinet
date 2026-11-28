package ru.creditcabinet.agent.infrastructure

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpHeaders
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import ru.creditcabinet.application.application.CreditFailure
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

@Component
@Profile("agent-mcp-remote")
internal class McpActorAssertionRedeemer(
    @Value("\${cabinet.mcp.identity-origin}") identityOrigin: URI,
    @Value("\${cabinet.mcp.identity-ca}") identityCa: Path,
    @Value("\${MCP_CREDIT_SERVICE_TOKEN}") private val serviceToken: String,
) {
    init {
        require(identityOrigin.scheme == "https" && identityOrigin.host != null && identityOrigin.rawUserInfo == null &&
            identityOrigin.rawPath.isEmpty() && identityOrigin.rawQuery == null && identityOrigin.rawFragment == null)
        require(serviceToken.length in 32..1024 && serviceToken.all { it in '!'..'~' })
    }

    private val endpoint = identityOrigin.resolve("/internal/mcp/redeem")
    private val trustedIdentity = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
        init(pemTrustStore(identityCa))
    }
    private val transport = HttpClient.newBuilder()
        .sslContext(SSLContext.getInstance("TLS").apply { init(null, trustedIdentity.trustManagers, null) })
        .connectTimeout(Duration.ofSeconds(2))
        .proxy(ProxySelector.of(null))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    private val client = RestClient.builder().requestFactory(
        JdkClientHttpRequestFactory(transport).apply { setReadTimeout(Duration.ofSeconds(3)) },
    ).build()

    fun redeem(assertion: String): UUID {
        if (assertion.length !in 32..8192 || assertion.any { it !in '!'..'~' || it == ',' }) {
            throw CreditFailure.Unauthenticated("Недействительный MCP assertion")
        }
        val actor = try {
            client.post().uri(endpoint)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $serviceToken")
                .body(AssertionRequest(assertion))
                .retrieve()
                .toEntity(Actor::class.java)
                .takeIf { it.statusCode.value() == 200 }?.body
        } catch (error: RestClientResponseException) {
            if (error.statusCode.value() in setOf(400, 401, 403, 404, 409)) {
                throw CreditFailure.Unauthenticated("Недействительный MCP assertion")
            }
            throw CreditFailure.DependencyUnavailable("Не удалось подтвердить MCP пользователя")
        } catch (_: RestClientException) {
            throw CreditFailure.DependencyUnavailable("Не удалось подтвердить MCP пользователя")
        } ?: throw CreditFailure.DependencyUnavailable("Не удалось подтвердить MCP пользователя")
        if (actor.role != "AGENT" || actor.actorId == null) throw CreditFailure.Forbidden("Недопустимый MCP пользователь")
        return actor.actorId
    }

    private data class AssertionRequest(val actorAssertion: String)

    private data class Actor(val actorId: UUID?, val role: String?)
}
