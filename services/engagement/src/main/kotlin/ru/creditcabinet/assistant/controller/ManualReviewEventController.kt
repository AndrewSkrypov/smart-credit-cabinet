package ru.creditcabinet.assistant.controller

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.HttpStatus
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.assistant.application.ManualReviewSummaries
import ru.creditcabinet.assistant.domain.ManualReviewEvent
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.outbox.DurableOutbox

@Hidden
@RestController
class ManualReviewEventController(private val outbox: DurableOutbox, private val mapper: ObjectMapper) {
    @Transactional
    @PostMapping("/internal/v1/assistant/manual-review-events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun accept(@RequestBody event: ManualReviewEvent) {
        if (event.schemaVersion != 1) throw ApiException(HttpStatus.BAD_REQUEST, "Версия события не поддерживается")
        outbox.enqueue(ManualReviewSummaries.CHANNEL, event.eventId.toString(), mapper.writeValueAsString(event))
    }
}
