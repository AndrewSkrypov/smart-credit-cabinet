package ru.creditcabinet.application.domain

import ru.creditcabinet.document.domain.DocumentType
import java.time.Instant
import java.util.UUID

/** One thing the bank needs from the client: a document of a type, or an answer in words. */
data class InfoItem(
    val documentType: DocumentType? = null,
    val question: String? = null,
    val reason: String? = null,
) {
    init {
        require((documentType == null) != (question.isNullOrBlank())) { "Пункт запроса — это документ или вопрос" }
    }
}

data class InfoRequest(
    val id: UUID = UUID.randomUUID(),
    val applicationId: UUID,
    val items: List<InfoItem>,
    val comment: String?,
    val deadline: Instant,
    val requestedBy: Actor,
    val createdAt: Instant,
    val resolvedAt: Instant? = null,
    val responseComment: String? = null,
) {
    val hasQuestions: Boolean get() = items.any { it.question != null }
}

interface InfoRequests {
    fun save(request: InfoRequest)
    fun open(applicationId: UUID): InfoRequest?
    fun resolve(id: UUID, at: Instant, responseComment: String?)
}
