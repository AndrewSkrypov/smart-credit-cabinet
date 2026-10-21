package ru.creditcabinet.application.infrastructure

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import ru.creditcabinet.application.domain.CompanyDirectory
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.common.exception.ApiException
import java.time.Duration
import java.util.UUID

/** Asks identity's internal API for the company a client acts for. */
@Component
class IdentityCompanyDirectory(
    @Value("\${cabinet.identity.url:}") identityUrl: String,
    builder: RestClient.Builder,
) : CompanyDirectory {
    private val client: RestClient? = identityUrl.takeIf { it.isNotBlank() }?.let {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(2))
            setReadTimeout(Duration.ofSeconds(5))
        }
        builder.clone().baseUrl(it).requestFactory(requestFactory).build()
    }

    private data class CompanyResponse(val id: UUID, val inn: String, val name: String?, val legalForm: LegalForm)

    override fun find(companyId: UUID): CompanySnapshot? {
        val client = client ?: throw unavailable()
        return try {
            client.get().uri("/internal/v1/companies/{id}", companyId).retrieve().body(CompanyResponse::class.java)
                ?.let { CompanySnapshot(it.id, it.inn, it.name, it.legalForm) }
        } catch (exception: HttpClientErrorException.NotFound) {
            null
        } catch (exception: Exception) {
            throw unavailable()
        }
    }

    private fun unavailable() = ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Сервис учётных записей недоступен — попробуйте через минуту")
}
