package ru.creditcabinet.application.service

import org.springframework.stereotype.Component
import ru.creditcabinet.application.domain.ApplicationJournal
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.domain.Transition
import ru.creditcabinet.common.notifications.PlatformNotifications
import ru.creditcabinet.product.domain.ProductCatalog

/**
 * Saves a moved application together with its journal entry, in the caller's transaction, and
 * hands the notifications about it to engagement once that transaction has committed.
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
        val productName = products.find(saved.terms.productCode)?.name ?: saved.terms.productCode
        notifications.send(TransitionNotices.of(saved, transition.entry, productName))
        return saved
    }
}
