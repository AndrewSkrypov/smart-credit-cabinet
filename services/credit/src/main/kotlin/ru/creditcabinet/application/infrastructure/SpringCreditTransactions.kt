package ru.creditcabinet.application.infrastructure

import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.application.application.CreditTransactions

@Component
class SpringCreditTransactions(private val transaction: TransactionTemplate) : CreditTransactions {
    override val active: Boolean get() = TransactionSynchronizationManager.isActualTransactionActive()

    override fun <T : Any> execute(action: () -> T): T =
        checkNotNull(transaction.execute { action() })
}
