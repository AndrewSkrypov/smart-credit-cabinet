package ru.creditcabinet.application.service

import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.advice.application.ManualReviewEvents
import ru.creditcabinet.application.application.CommitTransition
import ru.creditcabinet.application.domain.ApplicationJournal
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatusEvent
import ru.creditcabinet.application.domain.ApplicationStatusEvents
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.domain.Transition
import ru.creditcabinet.common.notifications.PlatformNotifications
import ru.creditcabinet.product.domain.ProductCatalog
import ru.creditcabinet.product.domain.forApplication

/**
 * Saves a moved application together with its journal entry, in the caller's transaction, and
 * persists its status event, notifications and manual-review warmup events in the same transaction
 * for independent delivery.
 */
@Component
class Transitions(
    private val applications: ApplicationRepository,
    private val journal: ApplicationJournal,
    private val notifications: PlatformNotifications,
    private val products: ProductCatalog,
    private val statusEvents: ApplicationStatusEvents,
    private val manualReviewEvents: ManualReviewEvents = ManualReviewEvents { _, _ -> },
) : CommitTransition {
    @Transactional(propagation = Propagation.MANDATORY)
    override fun commit(transition: Transition): LoanApplication {
        val saved = applications.save(transition.application)
        journal.append(transition.entry)
        if (transition.entry.isTransition) {
            statusEvents.append(
                ApplicationStatusEvent(
                    eventId = transition.entry.id,
                    applicationId = saved.id,
                    applicationVersion = checkNotNull(saved.version) { "Application version is missing after saving" },
                    status = saved.status,
                    occurredAt = transition.entry.occurredAt,
                ),
            )
        }
        val productName = products.forApplication(saved).name
        notifications.send(TransitionNotices.of(saved, transition.entry, productName))
        manualReviewEvents.onTransition(saved, transition.entry)
        return saved
    }
}
