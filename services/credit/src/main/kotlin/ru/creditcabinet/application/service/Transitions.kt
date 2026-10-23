package ru.creditcabinet.application.service

import org.springframework.stereotype.Component
import ru.creditcabinet.application.domain.ApplicationJournal
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.domain.Transition
import ru.creditcabinet.common.notifications.PlatformNotifications
import ru.creditcabinet.product.domain.ProductCatalog
import ru.creditcabinet.product.domain.forApplication

/**
 * Saves a moved application together with its journal entry, in the caller's transaction, and
 * persists its notifications in the same transaction for independent delivery.
 */
@Component
class Transitions(
    private val applications: ApplicationRepository,
    private val journal: ApplicationJournal,
    private val notifications: PlatformNotifications,
    private val products: ProductCatalog,
) {
    fun commit(transition: Transition): LoanApplication {
        val saved = applications.save(transition.application)
        journal.append(transition.entry)
        val productName = products.forApplication(saved).name
        notifications.send(TransitionNotices.of(saved, transition.entry, productName))
        return saved
    }
}
