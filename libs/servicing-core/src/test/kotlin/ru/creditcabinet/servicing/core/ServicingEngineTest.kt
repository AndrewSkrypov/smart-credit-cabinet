@file:Suppress("ktlint:standard:no-wildcard-imports")

package ru.creditcabinet.servicing.core

import ru.creditcabinet.servicing.contract.v1.*
import ru.creditcabinet.servicing.domain.*
import ru.creditcabinet.servicing.paymentreserve.domain.*
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import kotlin.test.*

class ServicingEngineTest {
    private val now = Instant.parse("2026-10-01T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val company = UUID.randomUUID()
    private val actor = ServicingActor(UUID.randomUUID(), company)

    private fun command(revolving: Boolean = true, amount: Long = 1_000_000) = OpenLoanCommand(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 7, company, UUID.randomUUID(), "a".repeat(64),
        "C-2026-1", amount, 12, BigDecimal("20.00"), revolving, now, LocalDate.parse("2027-10-01"),
    )

    @Test fun `duplicate command including restart returns durable receipt and first tranche exactly once`() {
        val db = Memory()
        val command = command()
        val first = db.engine(clock).open(command)
        val restarted = db.engine(clock)
        assertEquals(first, restarted.open(command))
        assertEquals(1, db.accounts.size)
        val account = assertNotNull(restarted.byApplication(command.applicationId))
        assertEquals(first.accountId, account.id)
        assertEquals(500_000, account.disbursedAmount)
        assertEquals(1, account.tranches.size)
        assertEquals(12, account.payments.size)
        assertEquals(BigDecimal("500000.00"), account.payments.fold(BigDecimal.ZERO) { sum, payment -> sum + payment.principalAmount })
        assertEquals(BigDecimal("0.00"), account.payments.last().remainingBalance)
    }

    @Test fun `same command id different payload or cycle cannot open second account`() {
        val db = Memory()
        val command = command()
        val engine = db.engine(clock)
        engine.open(command)
        assertFailsWith<ServicingConflict> { engine.open(command.copy(approvedLimit = 900_000)) }
        assertFailsWith<ServicingConflict> { engine.open(command.copy(cycleId = UUID.randomUUID(), commandId = UUID.randomUUID())) }
        assertEquals(1, db.accounts.size)
        assertEquals(1, db.commands.size)
    }

    @Test fun `regular loan opens full amount and early repayment closes precise balance`() {
        val db = Memory()
        val engine = db.engine(clock)
        val id = engine.open(command(false, 999_999)).accountId
        assertEquals(999_999, engine.byId(id)!!.disbursedAmount)
        assertEquals(0, engine.byId(id)!!.availableLimit)
        val paid = engine.payNext(id, actor)
        assertTrue(paid.outstandingPrincipal < BigDecimal("999999.00"))
        val closed = engine.repayEarly(id, actor)
        assertEquals(LoanAccountStatus.REPAID, closed.status)
        assertEquals(BigDecimal("0.00"), closed.outstandingPrincipal)
        assertTrue(closed.payments.all { it.status == LoanPaymentStatus.PAID })
    }

    @Test fun `receipt zero and delayed reserve 130000 retain business arithmetic and idempotent confirmation`() {
        val db = Memory()
        val engine = db.engine(clock)
        val id = engine.open(command()).accountId
        val input = PaymentReserveInputs(20_000, 180_000, 100_000, 50_000, LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-10"))
        val quote = engine.putReserve(id, ReserveCommand(actor, input))
        assertEquals(130_000, quote.requiredAmount)
        assertFalse(quote.canDraw)
        val delayed = engine.receipt(id, ReceiptCommand(actor, ReceiptEvent("delay-1", ReceiptStatus.DELAYED)))
        assertEquals(130_000, delayed.suggestedDraw)
        assertTrue(delayed.canDraw)
        val confirmed = engine.confirmReserve(id, ConfirmReserveCommand(actor, delayed.version))
        assertEquals(130_000, confirmed.drawnAmount)
        assertEquals(0, confirmed.requiredAmount)
        assertEquals(confirmed, engine.confirmReserve(id, ConfirmReserveCommand(actor, delayed.version)))
        assertEquals(2, engine.byId(id)!!.tranches.size)
        assertFailsWith<ServicingConflict> { engine.receipt(id, ReceiptCommand(actor, ReceiptEvent("delay-1", ReceiptStatus.RECEIVED))) }
        val receivedId = engine.open(command()).accountId
        engine.putReserve(receivedId, ReserveCommand(actor, input))
        val received = engine.receipt(receivedId, ReceiptCommand(actor, ReceiptEvent("received-1", ReceiptStatus.RECEIVED)))
        assertEquals(0, received.requiredAmount)
        assertEquals(0, received.suggestedDraw)
        assertFalse(received.canDraw)
    }

    @Test fun `shortfall 50000 prohibits partial reserve draw`() {
        val db = Memory()
        val engine = db.engine(clock)
        val id = engine.open(command(amount = 160_000)).accountId
        engine.putReserve(
            id,
            ReserveCommand(actor, PaymentReserveInputs(20_000, 180_000, 100_000, 50_000, LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-10"))),
        )
        val delayed = engine.receipt(id, ReceiptCommand(actor, ReceiptEvent("delay", ReceiptStatus.DELAYED)))
        assertEquals(80_000, delayed.suggestedDraw)
        assertEquals(50_000, delayed.shortfall)
        assertFalse(delayed.canDraw)
        assertFailsWith<ServicingConflict> { engine.confirmReserve(id, ConfirmReserveCommand(actor, delayed.version)) }
        assertEquals(1, engine.byId(id)!!.tranches.size)
    }

    @Test fun `reserve write failure rolls tranche and schedule back with same aggregate transaction`() {
        val db = Memory()
        val engine = db.engine(clock)
        val id = engine.open(command()).accountId
        engine.putReserve(
            id,
            ReserveCommand(actor, PaymentReserveInputs(20_000, 180_000, 100_000, 50_000, LocalDate.parse("2026-10-05"), LocalDate.parse("2026-10-10"))),
        )
        val delayed = engine.receipt(id, ReceiptCommand(actor, ReceiptEvent("delay", ReceiptStatus.DELAYED)))
        val before = engine.byId(id)
        db.failReserveSave = true
        assertFailsWith<IllegalStateException> { engine.confirmReserve(id, ConfirmReserveCommand(actor, delayed.version)) }
        assertEquals(before, engine.byId(id))
        db.failReserveSave = false
        assertEquals(130_000, engine.confirmReserve(id, ConfirmReserveCommand(actor, delayed.version)).drawnAmount)
    }

    @Test fun `concurrent draw cannot overspend and wrong company cannot mutate`() {
        val db = Memory()
        val engine = db.engine(clock)
        val id = engine.open(command()).accountId
        assertFailsWith<ServicingNotFound> { engine.payNext(id, actor.copy(companyId = UUID.randomUUID())) }
        val workers = Executors.newFixedThreadPool(2)
        try {
            val results = workers.invokeAll(List(2) { Callable { runCatching { engine.draw(id, DrawCommand(actor, 400_000)) }.isSuccess } }).map { it.get() }
            assertEquals(1, results.count { it })
            assertEquals(900_000, engine.byId(id)!!.disbursedAmount)
            assertEquals(2, engine.byId(id)!!.tranches.size)
        } finally {
            workers.shutdownNow()
        }
    }

    private class Memory : LoanAccountStore, PaymentReserveStore, OpeningLedger, ServicingTransaction {
        var accounts = mutableMapOf<UUID, LoanAccount>()
        var plans = mutableMapOf<UUID, PaymentReservePlan>()
        var events = mutableMapOf<Pair<UUID, String>, ReceiptEvent>()
        var commands = mutableMapOf<UUID, Pair<OpenLoanCommand, LoanOpenedReceipt>>()
        var failReserveSave = false
        fun engine(clock: Clock) = ServicingEngine(this, this, this, this, clock)

        @Synchronized override fun <T : Any> execute(action: () -> T): T {
            val oldAccounts = accounts.toMutableMap()
            val oldPlans = plans.toMutableMap()
            val oldEvents = events.toMutableMap()
            val oldCommands = commands.toMutableMap()
            return try {
                action()
            } catch (e: Exception) {
                accounts = oldAccounts
                plans = oldPlans
                events = oldEvents
                commands = oldCommands
                throw e
            }
        }
        override fun existsByApplicationId(applicationId: UUID) = accounts.values.any { it.applicationId == applicationId }
        override fun findByApplicationId(applicationId: UUID) = accounts.values.singleOrNull { it.applicationId == applicationId }
        override fun findById(id: UUID) = accounts[id]
        override fun findByIdForUpdate(id: UUID) = accounts[id]
        override fun create(account: LoanAccount) {
            check(!existsByApplicationId(account.applicationId))
            accounts[account.id] = account
        }
        override fun saveState(account: LoanAccount, newTranche: LoanTranche?) {
            accounts[account.id] = account
        }
        override fun find(loanId: UUID): PaymentReservePlan? = plans[loanId]
        override fun save(plan: PaymentReservePlan, actorId: UUID) {
            check(!failReserveSave)
            plans[plan.loanId] = plan
        }
        override fun findEvent(loanId: UUID, eventId: String) = events[loanId to eventId]
        override fun saveEvent(loanId: UUID, event: ReceiptEvent, planVersion: Long, actorId: UUID) {
            events[loanId to event.eventId] = event
        }
        override fun lockApplication(applicationId: UUID) {}
        override fun findOpening(applicationId: UUID) = commands[applicationId]
        override fun save(command: OpenLoanCommand, receipt: LoanOpenedReceipt) {
            commands[command.applicationId] = command to receipt
        }
    }
}
