package ru.creditcabinet.identity.infrastructure

import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.identity.application.IdentityTransactions

@Component
class SpringIdentityTransactions(manager: PlatformTransactionManager) : IdentityTransactions {
    private val writes = TransactionTemplate(manager)
    private val reads = TransactionTemplate(manager).apply { isReadOnly = true }

    override fun <T : Any> write(operation: () -> T): T = checkNotNull(writes.execute { operation() })

    override fun <T : Any> read(operation: () -> T): T = checkNotNull(reads.execute { operation() })
}
