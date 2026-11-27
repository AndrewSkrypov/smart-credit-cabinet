package ru.creditcabinet.assistant.controller

import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.assistant.application.ManualReviewEvent
import ru.creditcabinet.assistant.application.ManualReviewIngress
import java.util.UUID

data class ManualReviewEventBody(val schemaVersion: Int, val eventId: UUID, val applicationId: UUID)

@Hidden
@RestController
class ManualReviewEventController(private val ingress: ManualReviewIngress) {
    @PostMapping("/internal/v1/assistant/manual-review-events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun accept(@RequestBody event: ManualReviewEventBody) = assistantResponse {
        ingress.accept(ManualReviewEvent(event.schemaVersion, event.eventId, event.applicationId))
    }
}
