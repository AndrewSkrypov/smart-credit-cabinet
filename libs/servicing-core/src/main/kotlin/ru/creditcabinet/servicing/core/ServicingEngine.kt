@file:Suppress("ktlint:standard:no-wildcard-imports")

package ru.creditcabinet.servicing.core

import ru.creditcabinet.servicing.contract.v1.*
import ru.creditcabinet.servicing.domain.*
import ru.creditcabinet.servicing.paymentreserve.domain.*
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneId
import java.util.UUID

interface ServicingTransaction {
    /** Infrastructure checks writer fence and holds its shared lock until commit. */
    fun <T : Any> execute(action: () -> T): T
}

interface OpeningLedger {
    /** Lock covers the absent account too, serializing concurrent first deliveries. */
    fun lockApplication(applicationId: UUID)
    fun findOpening(applicationId: UUID): Pair<OpenLoanCommand, LoanOpenedReceipt>?
    fun save(command: OpenLoanCommand, receipt: LoanOpenedReceipt)
}

object OpeningFingerprint {
    fun of(c: OpenLoanCommand): String {
        val fields = listOf(
            c.schemaVersion, c.commandId, c.applicationId, c.cycleId, c.applicationVersion,
            c.companyId, c.contractSnapshotId, c.contractSha256, c.contractNumber, c.approvedLimit,
            c.termMonths, c.rate.stripTrailingZeros().toPlainString(), c.revolving, c.disbursedAt, c.maturityDate, c.currency,
        )
        val text = fields.joinToString("") {
            val value = it.toString()
            "${value.length}:$value"
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}

/** One transaction and one account lock own balances, tranches, schedule and reserves. */
class ServicingEngine(
    private val accounts: LoanAccountStore,
    private val plans: PaymentReserveStore,
    private val ledger: OpeningLedger,
    private val tx: ServicingTransaction,
    private val clock: Clock,
) : ServicingOperations {
    override fun open(command: OpenLoanCommand): LoanOpenedReceipt = tx.execute {
        ledger.lockApplication(command.applicationId)
        val fingerprint = OpeningFingerprint.of(command)
        val previous = ledger.findOpening(command.applicationId)
        if (previous != null) {
            if (previous.second.payloadSha256 != fingerprint) throw ServicingConflict("Другие условия открытия или цикл заявки")
            previous.second
        } else {
            if (accounts.findByApplicationId(command.applicationId) != null) throw ServicingConflict("Счёт уже импортирован; повторная выдача запрещена")
            val account = LoanAccount.open(
                command.applicationId, command.companyId, command.contractNumber,
                command.approvedLimit, command.termMonths, command.rate, command.revolving,
                command.disbursedAt, command.maturityDate, command.disbursedAt.atZone(ZONE).toLocalDate(),
            )
            val receipt = LoanOpenedReceipt(
                command.commandId,
                command.applicationId,
                command.cycleId,
                command.applicationVersion,
                account.id,
                fingerprint,
                clock.instant(),
            )
            accounts.create(account)
            ledger.save(command, receipt)
            receipt
        }
    }

    override fun byApplication(applicationId: UUID): LoanAccount? = checkedRead {
        accounts.findByApplicationId(applicationId)?.let { accounts.findByIdForUpdate(it.id) }
    }
    override fun byId(loanId: UUID): LoanAccount? = checkedRead { accounts.findByIdForUpdate(loanId) }
    override fun draw(loanId: UUID, command: DrawCommand): LoanAccount = tx.execute {
        once(loanId, command.actor, command.operationKey, "tranche:${command.amount}:${command.purpose?.trim().orEmpty()}") { account ->
            val updated = account.drawTranche(command.amount, command.purpose, clock.instant(), today())
            accounts.saveState(updated, updated.tranches.last())
            updated
        }
    }
    override fun payNext(loanId: UUID, command: PayNextCommand): LoanAccount = tx.execute {
        once(loanId, command.actor, command.operationKey, "payment:${command.paymentId}") { account ->
            account.payNext(command.paymentId, clock.instant()).also { accounts.saveState(it) }
        }
    }
    override fun repayEarly(loanId: UUID, actor: ServicingActor): LoanAccount = tx.execute {
        owned(loanId, actor).repayEarly(clock.instant(), today()).also { accounts.saveState(it) }
    }
    override fun agentSummary(applicationId: UUID): AgentPaymentReserveSummary? = checkedRead {
        accounts.findByApplicationId(applicationId)?.let {
            accounts.findByIdForUpdate(it.id)
        }?.let { account -> plans.find(account.id)?.quote(account, today())?.agentSummary(today()) }
    }
    override fun reserve(loanId: UUID): PaymentReserveQuote? = checkedRead {
        val account = accounts.findByIdForUpdate(loanId) ?: throw missing()
        plans.find(loanId)?.quote(account, today())
    }
    override fun putReserve(loanId: UUID, command: ReserveCommand): PaymentReserveQuote = tx.execute {
        val account = owned(loanId, command.actor)
        val current = plans.find(loanId)
        if (current?.inputs == command.inputs) {
            current.quote(account, today())
        } else {
            if (current?.confirmedDraw != null) throw ServicingConflict("По этому плану уже выполнена выборка")
            if (!account.revolving || account.status != LoanAccountStatus.ACTIVE) throw ServicingConflict("Резерв доступен по действующей возобновляемой линии")
            if (command.inputs.paymentDate < today() ||
                command.inputs.paymentDate > account.maturityDate
            ) {
                throw ServicingBadRequest("Дата платежа должна быть в пределах действующей линии")
            }
            val updated = PaymentReservePlan(loanId, (current?.version ?: 0L) + 1L, command.inputs)
            plans.save(updated, command.actor.userId)
            updated.quote(account, today())
        }
    }
    override fun receipt(loanId: UUID, command: ReceiptCommand): PaymentReserveQuote = tx.execute {
        val account = owned(loanId, command.actor)
        val current = plans.find(loanId) ?: throw missing()
        val prior = plans.findEvent(loanId, command.event.eventId)
        if (prior != null) {
            if (prior != command.event) throw ServicingConflict("Идентификатор уже использован другим событием")
            current.quote(account, today())
        } else {
            val amount = command.event.amountFor(current.inputs)
            if (amount < current.receivedAmount ||
                (current.receiptStatus == ReceiptStatus.RECEIVED && command.event.status == ReceiptStatus.DELAYED)
            ) {
                throw ServicingConflict("Событие противоречит подтверждённому поступлению")
            }
            val updated = if (current.receiptStatus == command.event.status && current.receivedAmount == amount) {
                current
            } else {
                current.copy(version = current.version + 1L, receiptStatus = command.event.status, receivedAmount = amount).also {
                    plans.save(it, command.actor.userId)
                }
            }
            plans.saveEvent(loanId, command.event, updated.version, command.actor.userId)
            updated.quote(account, today())
        }
    }
    override fun confirmReserve(loanId: UUID, command: ConfirmReserveCommand): PaymentReserveQuote = tx.execute {
        val account = owned(loanId, command.actor)
        val current = plans.find(loanId) ?: throw missing()
        if (current.confirmedDraw?.version == command.expectedVersion) {
            current.quote(account, today())
        } else {
            if (current.version != command.expectedVersion) throw ServicingConflict("План изменился. Обновите расчёт перед подтверждением")
            val quote = current.quote(account, today())
            if (!quote.canDraw) throw ServicingConflict("По текущему плану выборка недоступна. Проверьте поступление, срок и остаток лимита")
            val updatedAccount = account.drawTranche(quote.suggestedDraw, "Резерв к дате платежа", clock.instant(), today())
            val tranche = updatedAccount.tranches.last()
            accounts.saveState(updatedAccount, tranche)
            val updated = current.copy(
                version = current.version + 1L,
                confirmedDraw = ConfirmedReserveDraw(
                    command.expectedVersion,
                    tranche.id,
                    tranche.amount,
                    command.actor.userId,
                    tranche.disbursedAt,
                ),
            )
            plans.save(updated, command.actor.userId)
            updated.quote(updatedAccount, today())
        }
    }

    /**
     * Runs a client operation at most once per key under the account lock. A retry with the same
     * key and request returns the account as it is now; the same key with another request is a conflict.
     */
    private fun once(loanId: UUID, actor: ServicingActor, key: UUID?, request: String, change: (LoanAccount) -> LoanAccount): LoanAccount {
        val account = owned(loanId, actor)
        if (key == null) return change(account)
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(request.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        accounts.findOperation(loanId, key)?.let { receipt ->
            if (receipt.fingerprint != fingerprint) throw ServicingConflict("Этот ключ уже использован для другой операции по кредиту")
            return account
        }
        return change(account).also { accounts.recordOperation(loanId, LoanOperationReceipt(key, fingerprint)) }
    }

    private fun owned(id: UUID, actor: ServicingActor): LoanAccount =
        accounts.findByIdForUpdate(id)?.takeIf { it.companyId == actor.companyId } ?: throw missing()

    // Box permits nullable results while the infrastructure transaction API requires a non-null result.
    private fun <T> checkedRead(action: () -> T): T = tx.execute { Box(action()) }.value
    private data class Box<T>(val value: T)
    private fun today() = clock.instant().atZone(ZONE).toLocalDate()
    private fun missing() = ServicingNotFound("Кредитный счёт или резерв не найден")
    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Moscow")
    }
}
