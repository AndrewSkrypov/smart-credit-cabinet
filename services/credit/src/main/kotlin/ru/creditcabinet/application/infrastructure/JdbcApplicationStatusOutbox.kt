package ru.creditcabinet.application.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import ru.creditcabinet.application.domain.ApplicationStatusEvent
import ru.creditcabinet.application.domain.ApplicationStatusEvents
import ru.creditcabinet.common.outbox.DurableOutbox

internal const val APPLICATION_STATUS_CHANNEL = "application-status"

class JdbcApplicationStatusOutbox(
    internal val outbox: DurableOutbox,
    private val mapper: ObjectMapper,
) : ApplicationStatusEvents {
    override fun append(event: ApplicationStatusEvent) {
        val payload = mapper.createObjectNode()
            .put("eventId", event.eventId.toString())
            .put("applicationId", event.applicationId.toString())
            .put("applicationVersion", event.applicationVersion)
            .put("status", event.status.name)
            .put("occurredAt", event.occurredAt.toString())
        outbox.enqueue(APPLICATION_STATUS_CHANNEL, event.eventId.toString(), mapper.writeValueAsString(payload))
    }
}
