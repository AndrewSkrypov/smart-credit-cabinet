package ru.creditcabinet.assistant.domain

import com.fasterxml.jackson.databind.JsonNode
import java.util.UUID

data class ManualReviewEvent(val schemaVersion: Int, val eventId: UUID, val applicationId: UUID)

data class ManualReviewAdvice(val revision: String, val facts: JsonNode)
