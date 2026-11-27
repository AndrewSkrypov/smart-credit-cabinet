package ru.creditcabinet.financing.invoice.infrastructure

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import ru.creditcabinet.financing.invoice.application.InvoiceModel
import ru.creditcabinet.financing.invoice.application.InvoiceModelChoice
import java.time.Duration

@Component
internal class EngagementInvoiceModel(
    @Value("\${cabinet.engagement.url:}") url: String,
    builder: RestClient.Builder,
) : InvoiceModel {
    private val client = url.takeIf { it.isNotBlank() }?.let {
        val timeouts = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(1))
            setReadTimeout(Duration.ofSeconds(8))
        }
        builder.clone().baseUrl(it).requestFactory(timeouts).build()
    }

    override fun select(amountQuotes: List<String>, dateQuotes: List<String>, authorization: String): InvoiceModelChoice? =
        try {
            client?.post()?.uri("/internal/v1/assistant/invoice-facts")
                ?.header(HttpHeaders.AUTHORIZATION, authorization)?.contentType(MediaType.APPLICATION_JSON)
                ?.body(mapOf("amounts" to amountQuotes, "dates" to dateQuotes))
                ?.retrieve()?.body(InvoiceModelChoice::class.java)
        } catch (_: RestClientException) {
            null
        }
}
