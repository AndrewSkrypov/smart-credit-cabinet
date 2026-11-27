package ru.creditcabinet.assistant.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration

/** The credit service's product matcher, called with the person's own token. */
@Component
class CreditProducts(
    @Value("\${cabinet.credit.url:}") creditUrl: String,
    builder: RestClient.Builder,
) {
    private val client: RestClient? = creditUrl.takeIf { it.isNotBlank() }?.let {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(2))
            setReadTimeout(Duration.ofSeconds(5))
        }
        builder.clone().baseUrl(it).requestFactory(requestFactory).build()
    }

    fun purposes(authorization: String): Map<String, String> =
        runCatching {
            client?.get()?.uri("/api/v1/products/purposes")?.header(HttpHeaders.AUTHORIZATION, authorization)?.retrieve()?.body(JsonNode::class.java)
                ?.associate { it["code"].asText() to it["title"].asText() }
        }.getOrNull().orEmpty()

    fun match(purpose: String, amount: Long, termMonths: Int, authorization: String): JsonNode? =
        runCatching {
            client?.post()?.uri("/api/v1/products/match")?.header(HttpHeaders.AUTHORIZATION, authorization)
                ?.contentType(MediaType.APPLICATION_JSON)
                ?.body(mapOf("purpose" to purpose, "amount" to amount, "termMonths" to termMonths))
                ?.retrieve()?.body(JsonNode::class.java)
        }.getOrNull()

    fun catalogue(authorization: String): JsonNode? =
        runCatching { client?.get()?.uri("/api/v1/products")?.header(HttpHeaders.AUTHORIZATION, authorization)?.retrieve()?.body(JsonNode::class.java) }
            .getOrNull()
}
