package ru.creditcabinet.analytics.domain

import java.time.Instant
import java.util.UUID

enum class ApplicationStatus {
    DRAFT,
    PENDING_CLIENT_CONFIRMATION,
    SUBMITTED,
    INFO_REQUESTED,
    ENRICHMENT,
    SCORING,
    MANUAL_REVIEW,
    OFFER_ISSUED,
    SIGNING,
    DISBURSING,
    DISBURSED,
    REJECTED,
    WITHDRAWN,
    CLOSED,
    ;

    val visibleInFunnel: Boolean
        get() = this != DRAFT && this != PENDING_CLIENT_CONFIRMATION
}

data class VersionedStatus(val applicationId: UUID, val applicationVersion: Long, val status: ApplicationStatus) {
    init {
        require(applicationVersion >= 0)
    }
}

data class StatusEvent(val eventId: UUID, val state: VersionedStatus, val occurredAt: Instant)
