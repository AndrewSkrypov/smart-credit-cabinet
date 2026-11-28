package ru.creditcabinet.notification.infrastructure

import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.notification.application.NotificationDeliveryTransaction

@Component
class SpringNotificationDeliveryTransaction(transactionManager: PlatformTransactionManager) : NotificationDeliveryTransaction {
    private val transaction = TransactionTemplate(transactionManager)

    override fun execute(delivery: () -> Int): Int = checkNotNull(transaction.execute { delivery() })
}
