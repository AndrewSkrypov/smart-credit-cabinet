package ru.creditcabinet.application.domain

import ru.creditcabinet.application.domain.ActorType.CLIENT
import ru.creditcabinet.application.domain.ActorType.MANAGER
import ru.creditcabinet.application.domain.ActorType.SYSTEM
import ru.creditcabinet.application.domain.ActorType.UNDERWRITER
import ru.creditcabinet.application.domain.ApplicationStatus.CLOSED
import ru.creditcabinet.application.domain.ApplicationStatus.DISBURSED
import ru.creditcabinet.application.domain.ApplicationStatus.DISBURSING
import ru.creditcabinet.application.domain.ApplicationStatus.DRAFT
import ru.creditcabinet.application.domain.ApplicationStatus.ENRICHMENT
import ru.creditcabinet.application.domain.ApplicationStatus.INFO_REQUESTED
import ru.creditcabinet.application.domain.ApplicationStatus.MANUAL_REVIEW
import ru.creditcabinet.application.domain.ApplicationStatus.OFFER_ISSUED
import ru.creditcabinet.application.domain.ApplicationStatus.REJECTED
import ru.creditcabinet.application.domain.ApplicationStatus.SCORING
import ru.creditcabinet.application.domain.ApplicationStatus.SIGNING
import ru.creditcabinet.application.domain.ApplicationStatus.SUBMITTED
import ru.creditcabinet.application.domain.ApplicationStatus.WITHDRAWN

/** Events of docs/state-machine.md, section 3, with the actors allowed to cause them. */
enum class ApplicationEvent(val actors: Set<ActorType>) {
    SUBMIT(setOf(CLIENT)),
    DATA_COMPLETE(setOf(SYSTEM)),
    DATA_INCOMPLETE(setOf(SYSTEM)),
    INFO_PROVIDED(setOf(CLIENT)),
    INFO_EXPIRED(setOf(SYSTEM)),
    SOURCE_FAILED(setOf(SYSTEM)),
    ENRICHMENT_DONE(setOf(SYSTEM)),
    SOURCE_TIMED_OUT(setOf(SYSTEM)),
    AUTO_OFFER(setOf(SYSTEM)),
    TO_MANUAL_REVIEW(setOf(SYSTEM)),
    AUTO_REJECT(setOf(SYSTEM)),
    APPROVE(setOf(UNDERWRITER)),
    COUNTER_OFFER(setOf(UNDERWRITER)),

    // A manager asks for documents too: completeness is their job (docs/process-model.md, section 2).
    REQUEST_INFO(setOf(UNDERWRITER, MANAGER)),
    REJECT(setOf(UNDERWRITER)),
    ACCEPT_OFFER(setOf(CLIENT)),
    DECLINE_OFFER(setOf(CLIENT)),
    OFFER_EXPIRED(setOf(SYSTEM)),
    SIGN(setOf(CLIENT)),
    DISBURSEMENT_FAILED(setOf(SYSTEM)),
    DISBURSED(setOf(SYSTEM)),
    WITHDRAW(setOf(CLIENT)),
}

/**
 * The transition table, T1–T21 of docs/state-machine.md. Anything not listed is refused: the
 * caller gets 409 with the reason, not a silently changed status.
 */
object ApplicationStateMachine {
    // Declared before the table, which reads it while initialising.
    val WITHDRAWABLE = setOf(DRAFT, SUBMITTED, INFO_REQUESTED, ENRICHMENT, MANUAL_REVIEW, OFFER_ISSUED, SIGNING)

    private val table: Map<Pair<ApplicationStatus, ApplicationEvent>, ApplicationStatus> = buildMap {
        put(DRAFT to ApplicationEvent.SUBMIT, SUBMITTED)
        put(SUBMITTED to ApplicationEvent.DATA_COMPLETE, ENRICHMENT)
        put(SUBMITTED to ApplicationEvent.DATA_INCOMPLETE, INFO_REQUESTED)
        put(INFO_REQUESTED to ApplicationEvent.INFO_PROVIDED, SUBMITTED)
        put(INFO_REQUESTED to ApplicationEvent.INFO_EXPIRED, CLOSED)
        put(ENRICHMENT to ApplicationEvent.SOURCE_FAILED, ENRICHMENT)
        put(ENRICHMENT to ApplicationEvent.ENRICHMENT_DONE, SCORING)
        put(ENRICHMENT to ApplicationEvent.SOURCE_TIMED_OUT, MANUAL_REVIEW)
        put(SCORING to ApplicationEvent.AUTO_OFFER, OFFER_ISSUED)
        put(SCORING to ApplicationEvent.TO_MANUAL_REVIEW, MANUAL_REVIEW)
        put(SCORING to ApplicationEvent.AUTO_REJECT, REJECTED)
        put(MANUAL_REVIEW to ApplicationEvent.APPROVE, OFFER_ISSUED)
        put(MANUAL_REVIEW to ApplicationEvent.COUNTER_OFFER, OFFER_ISSUED)
        put(MANUAL_REVIEW to ApplicationEvent.REQUEST_INFO, INFO_REQUESTED)
        put(MANUAL_REVIEW to ApplicationEvent.REJECT, REJECTED)
        put(OFFER_ISSUED to ApplicationEvent.ACCEPT_OFFER, SIGNING)
        put(OFFER_ISSUED to ApplicationEvent.DECLINE_OFFER, CLOSED)
        put(OFFER_ISSUED to ApplicationEvent.OFFER_EXPIRED, CLOSED)
        put(SIGNING to ApplicationEvent.SIGN, DISBURSING)
        put(DISBURSING to ApplicationEvent.DISBURSEMENT_FAILED, DISBURSING)
        put(DISBURSING to ApplicationEvent.DISBURSED, DISBURSED)
        // T21: a client may withdraw until the money is on its way, but not while a system
        // step is running (SCORING, DISBURSING): those finish within seconds.
        for (status in WITHDRAWABLE) put(status to ApplicationEvent.WITHDRAW, WITHDRAWN)
    }

    fun target(from: ApplicationStatus, event: ApplicationEvent): ApplicationStatus? = table[from to event]

    /** Events possible from [status] for [actor]: what the interface may offer as buttons. */
    fun eventsFrom(status: ApplicationStatus, actor: ActorType): List<ApplicationEvent> =
        ApplicationEvent.entries.filter { actor in it.actors && table.containsKey(status to it) }

    /** Every allowed (from, event, to); used by tests to compare with the documented table. */
    fun transitions(): Set<Triple<ApplicationStatus, ApplicationEvent, ApplicationStatus>> =
        table.map { (key, to) -> Triple(key.first, key.second, to) }.toSet()
}
