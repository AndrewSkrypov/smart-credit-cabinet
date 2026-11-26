package ru.creditcabinet.analytics.application

import ru.creditcabinet.analytics.domain.StatusEvent
import ru.creditcabinet.analytics.domain.VersionedStatus

interface UpdateStatusProjection {
    /** Returns after the receipt and any projection update have committed. */
    fun applyEvent(event: StatusEvent): StatusUpdateResult

    fun applySnapshot(state: VersionedStatus): StatusUpdateResult
}

interface CurrentStatuses {
    fun currentStatuses(): Map<String, Int>
}

enum class StatusUpdateResult {
    APPLIED,
    STALE,
    UNCHANGED,
    DUPLICATE,
    VERSION_CONFLICT,
    EVENT_ID_CONFLICT,
}
