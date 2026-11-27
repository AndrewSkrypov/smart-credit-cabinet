package ru.creditcabinet.advice.application

import ru.creditcabinet.application.domain.JournalEntry
import ru.creditcabinet.application.domain.LoanApplication
import java.util.UUID

data class ManualReviewEventPayload(
    val schemaVersion: Int,
    val eventId: UUID,
    val applicationId: UUID,
)

/** Records a durable outbox event whenever an application enters MANUAL_REVIEW. */
fun interface ManualReviewEvents {
    fun onTransition(application: LoanApplication, entry: JournalEntry)
}
