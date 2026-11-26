package ru.creditcabinet.application.domain

import java.time.Instant
import java.util.UUID

data class ApplicationStatusEvent(
    val eventId: UUID,
    val applicationId: UUID,
    val applicationVersion: Long,
    val status: ApplicationStatus,
    val occurredAt: Instant,
)

/** Appends the saved status and version in the transaction that writes the journal entry. */
fun interface ApplicationStatusEvents {
    fun append(event: ApplicationStatusEvent)
}
