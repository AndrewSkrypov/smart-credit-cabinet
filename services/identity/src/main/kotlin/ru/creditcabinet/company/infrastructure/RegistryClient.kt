package ru.creditcabinet.company.infrastructure

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import ru.creditcabinet.company.domain.CompanyRegistry
import ru.creditcabinet.company.domain.RegistryAnswer
import java.time.Duration

/**
 * ЕГРЮЛ/ЕГРИП through the registry gateway (the external-sim service locally). Short timeouts:
 * sign-up must not hang on a slow registry, it goes on without the name instead.
 */
@Component
class RegistryClient(
    @Value("\${cabinet.registry.url:}") url: String,
    @Value("\${cabinet.registry.api-key:}") apiKey: String,
    builder: RestClient.Builder,
) : CompanyRegistry {
    private val log = LoggerFactory.getLogger(javaClass)

    private val client: RestClient? = url.takeIf { it.isNotBlank() }?.let {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(2))
            setReadTimeout(Duration.ofSeconds(4))
        }
        builder.clone().baseUrl(it).defaultHeader("X-Api-Key", apiKey).requestFactory(requestFactory).build()
    }

    private data class Extract(val shortName: String)

    override fun lookup(inn: String): RegistryAnswer {
        val client = client ?: return RegistryAnswer.Unavailable
        return try {
            client.get().uri("/ext/registry/v1/companies/{inn}", inn).retrieve().body(Extract::class.java)
                ?.let { RegistryAnswer.Found(it.shortName) } ?: RegistryAnswer.Unavailable
        } catch (exception: HttpClientErrorException.NotFound) {
            RegistryAnswer.NotFound
        } catch (exception: Exception) {
            log.warn("Registry lookup failed, sign-up continues without the company name: {}", exception.message)
            RegistryAnswer.Unavailable
        }
    }
}
