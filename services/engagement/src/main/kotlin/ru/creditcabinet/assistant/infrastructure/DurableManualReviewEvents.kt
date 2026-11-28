package ru.creditcabinet.assistant.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.assistant.application.ManualReviewEvent
import ru.creditcabinet.assistant.application.ManualReviewEvents
import ru.creditcabinet.assistant.application.ManualReviewSummaries
import ru.creditcabinet.common.outbox.DeliveryHandler
import ru.creditcabinet.common.outbox.DurableOutbox
import java.util.UUID

private const val CHANNEL = "assistant-manual-review"

private data class ManualReviewMessage(val schemaVersion: Int, val eventId: UUID, val applicationId: UUID)

@Component
class DurableManualReviewEvents(private val outbox: DurableOutbox, private val mapper: ObjectMapper) : ManualReviewEvents {
    @Transactional
    override fun enqueue(event: ManualReviewEvent) {
        val message = ManualReviewMessage(event.schemaVersion, event.eventId, event.applicationId)
        outbox.enqueue(CHANNEL, event.eventId.toString(), mapper.writeValueAsString(message))
    }
}

@Component
class ManualReviewEventDelivery(private val summaries: ManualReviewSummaries, private val mapper: ObjectMapper) : DeliveryHandler {
    override val channel = CHANNEL

    override fun deliver(payload: String) {
        if (!summaries.enabled) return
        val message = mapper.readValue(payload, ManualReviewMessage::class.java)
        summaries.deliver(ManualReviewEvent(message.schemaVersion, message.eventId, message.applicationId))
    }
}
