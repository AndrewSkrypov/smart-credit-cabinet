package ru.creditcabinet.servicing.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.servicing.core.ServicingEngine
import ru.creditcabinet.servicing.domain.LoanAccountStore
import ru.creditcabinet.servicing.paymentreserve.domain.PaymentReserveStore
import ru.creditcabinet.servicing.persistence.JdbcOpeningLedger
import ru.creditcabinet.servicing.persistence.JdbcServicingTransaction
import ru.creditcabinet.platform.database.SchemaMigration
import java.time.Clock

@SpringBootApplication(scanBasePackages = ["ru.creditcabinet.servicing", "ru.creditcabinet.common", "ru.creditcabinet.platform.database"])
class ServicingApplication {
    @Bean fun servicingClock(): Clock = Clock.systemUTC()
    @Bean fun servicingEngine(accounts: LoanAccountStore, plans: PaymentReserveStore, jdbc: JdbcTemplate,
        tx: TransactionTemplate, mapper: ObjectMapper, clock: Clock): ServicingEngine =
        ServicingEngine(accounts, plans, JdbcOpeningLedger(jdbc, mapper), JdbcServicingTransaction(jdbc, tx, "REMOTE"), clock)
}
fun main(args: Array<String>) {
    if (SchemaMigration.runIfRequested("servicing")) return
    runApplication<ServicingApplication>(*args)
}
