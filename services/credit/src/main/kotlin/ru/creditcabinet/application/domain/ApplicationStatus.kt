package ru.creditcabinet.application.domain

/** Statuses of docs/state-machine.md, section 2. */
enum class ApplicationStatus(val terminal: Boolean = false) {
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
    DISBURSED(terminal = true),
    REJECTED(terminal = true),
    WITHDRAWN(terminal = true),
    CLOSED(terminal = true),
}

/** Why an application ended up CLOSED. */
enum class CloseReason { INFO_EXPIRED, OFFER_DECLINED, OFFER_EXPIRED }

/** Lifecycle of an agent's commission for an application. */
enum class CommissionStatus { NONE, EXPECTED, ACCRUED, CANCELLED }

/** Who may cause an event. The system acts only from the pipeline worker, never through the public API. */
enum class ActorType { CLIENT, SYSTEM, MANAGER, UNDERWRITER, AGENT }

data class Actor(val type: ActorType, val id: java.util.UUID?) {
    companion object {
        val SYSTEM = Actor(ActorType.SYSTEM, null)
    }
}
