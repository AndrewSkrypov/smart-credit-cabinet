@file:Suppress("ktlint:standard:no-wildcard-imports")

package ru.creditcabinet.servicing.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.servicing.contract.v1.*
import ru.creditcabinet.servicing.core.ServicingEngine
import ru.creditcabinet.servicing.domain.*
import ru.creditcabinet.servicing.infrastructure.LoanServicingJdbcStore
import ru.creditcabinet.servicing.paymentreserve.domain.*
import ru.creditcabinet.servicing.paymentreserve.infrastructure.JdbcPaymentReserveStore
import ru.creditcabinet.servicing.persistence.*
import java.math.BigDecimal
import java.time.*
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.*

/** Runs only against the isolated migrated PostgreSQL supplied by the local acceptance harness. */
@EnabledIfEnvironmentVariable(named = "ALPHA_SERVICING_TEST_JDBC_URL", matches = ".+")
class ServicingPostgresIntegrationTest {
    private val source =
        DriverManagerDataSource(System.getenv("ALPHA_SERVICING_TEST_JDBC_URL"), System.getenv("ALPHA_SERVICING_TEST_DB_USER") ?: "servicing_app", "")
    private val jdbc = JdbcTemplate(source)
    private val tx = TransactionTemplate(DataSourceTransactionManager(source))
    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val clock = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC)
    private fun engine(
        plans: PaymentReserveStore = JdbcPaymentReserveStore(jdbc),
    ) = ServicingEngine(LoanServicingJdbcStore(jdbc), plans, JdbcOpeningLedger(jdbc, mapper), JdbcServicingTransaction(jdbc, tx, "REMOTE"), clock)
    private fun command() = OpenLoanCommand(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 5, UUID.randomUUID(), UUID.randomUUID(),
        "a".repeat(
            64,
        ),
        "T-${UUID.randomUUID().toString().take(8)}", 1_000_000, 12, BigDecimal("20.00"), true, clock.instant(), LocalDate.parse("2027-10-01"),
    )

    @Test fun `concurrent opening persists one receipt account and initial tranche across adapters`() {
        val command = command()
        val workers = Executors.newFixedThreadPool(2)
        try {
            val receipts = workers.invokeAll(List(2) { Callable { engine().open(command) } }).map { it.get() }
            assertEquals(receipts[0], receipts[1])
            assertEquals(receipts[0], engine().open(command))
            assertEquals(1, jdbc.queryForObject("select count(*) from loan_accounts where application_id=?", Int::class.java, command.applicationId))
            assertEquals(1, jdbc.queryForObject("select count(*) from loan_tranches where loan_account_id=?", Int::class.java, receipts[0].accountId))
            assertFailsWith<ServicingConflict> { engine().open(command.copy(approvedLimit = 900_000)) }
            assertFailsWith<ServicingConflict> { engine().open(command.copy(commandId = UUID.randomUUID(), cycleId = UUID.randomUUID())) }
        } finally {
            workers.shutdownNow()
        }
    }

    @Test fun `reserve confirmation rollback restores real account schedule and plan`() {
        val command = command()
        val engine = engine()
        val id = engine.open(command).accountId
        val actor = ServicingActor(UUID.randomUUID(), command.companyId)
        engine.putReserve(
            id,
            ReserveCommand(actor, PaymentReserveInputs(20_000, 180_000, 100_000, 50_000, LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-10"))),
        )
        val delayed = engine.receipt(id, ReceiptCommand(actor, ReceiptEvent("late", ReceiptStatus.DELAYED)))
        val before = engine.byId(id)
        val realPlans = JdbcPaymentReserveStore(jdbc)
        val broken = object : PaymentReserveStore by realPlans {
            override fun save(plan: PaymentReservePlan, actorId: UUID) {
                realPlans.save(plan, actorId)
                error("crash after reserve write")
            }
        }
        assertFailsWith<IllegalStateException> { engine(broken).confirmReserve(id, ConfirmReserveCommand(actor, delayed.version)) }
        assertEquals(before, engine.byId(id))
        assertEquals(delayed, engine.reserve(id))
        val confirmed = engine.confirmReserve(id, ConfirmReserveCommand(actor, delayed.version))
        assertEquals(130_000L, confirmed.drawnAmount)
        assertEquals(confirmed, engine().confirmReserve(id, ConfirmReserveCommand(actor, delayed.version)))
        assertEquals(2, jdbc.queryForObject("select count(*) from loan_tranches where loan_account_id=?", Int::class.java, id))
    }

    @Test fun `ordinary tranche racing reserve cannot overspend or produce a partial reserve`() {
        val command = command()
        val engine = engine()
        val id = engine.open(command).accountId
        val actor = ServicingActor(UUID.randomUUID(), command.companyId)
        engine.putReserve(
            id,
            ReserveCommand(actor, PaymentReserveInputs(20_000, 180_000, 100_000, 50_000, LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-10"))),
        )
        val quote = engine.receipt(id, ReceiptCommand(actor, ReceiptEvent("late", ReceiptStatus.DELAYED)))
        val workers = Executors.newFixedThreadPool(2)
        try {
            val results = workers.invokeAll(
                listOf(
                    Callable {
                        runCatching { engine().draw(id, DrawCommand(actor, 450_000)) }.isSuccess
                    },
                    Callable { runCatching { engine().confirmReserve(id, ConfirmReserveCommand(actor, quote.version)) }.isSuccess },
                ),
            ).map { it.get() }
            assertEquals(1, results.count { it })
            val current = engine.byId(id)!!
            assertTrue(current.disbursedAmount <= command.approvedLimit)
            assertEquals(2, current.tranches.size)
            val reserved = engine.reserve(id)!!
            assertTrue(reserved.drawnAmount == 0L || reserved.drawnAmount == 130_000L)
        } finally {
            workers.shutdownNow()
        }
        assertFailsWith<ServicingNotFound> { engine.payNext(id, actor.copy(companyId = UUID.randomUUID())) }
    }
}
