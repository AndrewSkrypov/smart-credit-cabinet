package ru.creditcabinet.offer.infrastructure

import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.offer.application.OfferTransactions

@Component
class SpringOfferTransactions(private val transaction: TransactionTemplate) : OfferTransactions {
    override fun <T : Any> execute(action: () -> T): T =
        checkNotNull(transaction.execute { action() })
}
