package ru.creditcabinet.advice.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import ru.creditcabinet.advice.application.ManualReviewEventPayload
import ru.creditcabinet.advice.application.ManualReviewEvents
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.JournalEntry
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.common.outbox.DeliveryHandler
import ru.creditcabinet.common.outbox.DurableOutbox
import java.time.Duration

@Component
class OutboxManualReviewEvents(
    private val outbox: ObjectProvider<DurableOutbox>,
    private val mapper: ObjectMapper,
) : ManualReviewEvents {
    override fun onTransition(application: LoanApplication, entry: JournalEntry) {
        if (entry.toStatus != ApplicationStatus.MANUAL_REVIEW || entry.fromStatus == ApplicationStatus.MANUAL_REVIEW) return
        val payload = mapper.writeValueAsString(ManualReviewEventPayload(SCHEMA_VERSION, entry.id, application.id))
        outbox.ifAvailable?.enqueue(CHANNEL, entry.id.toString(), payload)
    }

    companion object {
        const val CHANNEL = "assistant-manual-review"
        const val SCHEMA_VERSION = 1
    }
}

/** Delivers persisted manual-review transition events to engagement's internal warmup endpoint. */
@Component
@ConditionalOnProperty(name = ["cabinet.outbox.enabled"], havingValue = "true")
class EngagementManualReviewDelivery(
    @Value("\${cabinet.engagement.url:}") engagementUrl: String,
    builder: RestClient.Builder,
) : DeliveryHandler {
    override val channel = OutboxManualReviewEvents.CHANNEL

    private val client: RestClient = builder.clone()
        .baseUrl(engagementUrl)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(2))
                setReadTimeout(Duration.ofSeconds(5))
            },
        )
        .build()

    override fun deliver(payload: String) {
        client.post()
            .uri("/internal/v1/assistant/manual-review-events")
            .contentType(MediaType.APPLICATION_JSON)
            .body(payload)
            .retrieve()
            .toBodilessEntity()
    }
}
