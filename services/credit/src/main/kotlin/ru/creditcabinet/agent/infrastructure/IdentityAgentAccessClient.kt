package ru.creditcabinet.agent.infrastructure

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import ru.creditcabinet.agent.domain.AgentAccessDecision
import ru.creditcabinet.agent.domain.AgentAccessPort
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.common.exception.ApiException
import java.time.Duration
import java.util.UUID

/**
 * Verifies agent mandates against identity's internal API.
 * Strictly fail-closed: any connectivity or HTTP failure throws 503 SERVICE_UNAVAILABLE.
 */
@Component
class IdentityAgentAccessClient(
    @Value("\${cabinet.identity.url:}") identityUrl: String,
    builder: RestClient.Builder,
) : AgentAccessPort {
    private val logger = LoggerFactory.getLogger(IdentityAgentAccessClient::class.java)

    private val client: RestClient? = identityUrl.takeIf { it.isNotBlank() }?.let {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(2))
            setReadTimeout(Duration.ofSeconds(5))
        }
        builder.clone().baseUrl(it).requestFactory(requestFactory).build()
    }

    private data class CompanyResponse(val id: UUID, val inn: String, val name: String?, val legalForm: LegalForm)
    private data class AgentAccessResponse(val allowed: Boolean, val company: CompanyResponse?)

    override fun listCompanyIds(agentId: UUID): List<UUID> {
        val rest = client ?: throw unavailable()
        return try {
            rest.get()
                .uri("/internal/v1/agents/{agentId}/companies", agentId)
                .retrieve()
                .body(Array<UUID>::class.java)
                ?.toList()
                .orEmpty()
        } catch (exception: Exception) {
            logger.warn("Identity agent access check failed for agentId={}: {}", agentId, exception.message)
            throw unavailable()
        }
    }

    override fun checkAccess(agentId: UUID, companyId: UUID): AgentAccessDecision {
        val rest = client ?: throw unavailable()
        return try {
            val response = rest.get()
                .uri("/internal/v1/agents/{agentId}/companies/{companyId}/access", agentId, companyId)
                .retrieve()
                .body(AgentAccessResponse::class.java)
                ?: return AgentAccessDecision(allowed = false, company = null)
            AgentAccessDecision(
                allowed = response.allowed,
                company = response.company?.let { CompanySnapshot(it.id, it.inn, it.name, it.legalForm) },
            )
        } catch (exception: Exception) {
            logger.warn("Identity agent access check failed for agentId={}: {}", agentId, exception.message)
            throw unavailable()
        }
    }

    private fun unavailable() =
        ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Сервис учётных записей недоступен — попробуйте через минуту")
}
