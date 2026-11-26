package ru.creditcabinet.servicing.paymentreserve.application

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.agent.application.AgentApplicationService
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.servicing.application.LoanServicingService
import ru.creditcabinet.servicing.domain.LoanAccount
import ru.creditcabinet.servicing.domain.LoanAccountStatus
import ru.creditcabinet.servicing.domain.LoanAccountStore
import ru.creditcabinet.servicing.paymentreserve.domain.AgentPaymentReserveSummary
import ru.creditcabinet.servicing.paymentreserve.domain.ConfirmedReserveDraw
import ru.creditcabinet.servicing.paymentreserve.domain.PaymentReserveInputs
import ru.creditcabinet.servicing.paymentreserve.domain.PaymentReservePlan
import ru.creditcabinet.servicing.paymentreserve.domain.PaymentReserveQuote
import ru.creditcabinet.servicing.paymentreserve.domain.PaymentReserveStore
import ru.creditcabinet.servicing.paymentreserve.domain.ReceiptEvent
import ru.creditcabinet.servicing.paymentreserve.domain.ReceiptStatus
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@Service
class PaymentReserveService(
    private val loans: LoanAccountStore,
    private val applications: ApplicationRepository,
    private val plans: PaymentReserveStore,
    private val servicing: LoanServicingService,
    private val agentApplications: AgentApplicationService,
    private val clock: Clock,
    @Value("\${DEMO_ACCOUNTS:false}") private val demoAccounts: Boolean,
) {
    @Transactional(readOnly = true)
    fun get(loanId: UUID): PaymentReserveQuote? {
        val (_, companyId) = CurrentCaller.requireClient()
        val account = readOwnedAccount(loanId, companyId)
        return plans.find(account.id)?.quote(account, today())
    }

    @Transactional(readOnly = true)
    fun agentSummary(applicationId: UUID): AgentPaymentReserveSummary? {
        requireDemo()
        val (_, application) = agentApplications.ownAgentApplication(applicationId)
        if (!application.company.inn.startsWith(SYNTHETIC_INN_PREFIX)) throw missingReserve()
        val account = loans.findByApplicationId(applicationId) ?: return null
        val now = today()
        return plans.find(account.id)?.quote(account, now)?.agentSummary(now)
    }

    @Transactional
    fun put(loanId: UUID, inputs: PaymentReserveInputs): PaymentReserveQuote {
        val (caller, companyId) = CurrentCaller.requireClient()
        val account = lockOwnedAccount(loanId, companyId)
        val current = plans.find(account.id)
        val now = today()
        if (current?.inputs == inputs) return current.quote(account, now)
        if (current?.confirmedDraw != null) throw ConflictException("По этому плану уже выполнена выборка")
        if (!account.revolving || account.status != LoanAccountStatus.ACTIVE) {
            throw ConflictException("Резерв доступен по действующей возобновляемой линии")
        }
        if (inputs.paymentDate < now || inputs.paymentDate > account.maturityDate) {
            throw BadRequestException("Дата платежа должна быть в пределах действующей линии")
        }
        val updated = PaymentReservePlan(account.id, (current?.version ?: 0L) + 1L, inputs)
        plans.save(updated, caller.userId)
        return updated.quote(account, now)
    }

    @Transactional
    fun receipt(loanId: UUID, event: ReceiptEvent): PaymentReserveQuote {
        val (caller, companyId) = CurrentCaller.requireClient()
        val account = lockOwnedAccount(loanId, companyId)
        val current = plans.find(account.id) ?: throw missingReserve()
        val now = today()
        val prior = plans.findEvent(account.id, event.eventId)
        if (prior != null) {
            if (prior != event) throw ConflictException("Идентификатор уже использован другим событием")
            return current.quote(account, now)
        }

        val amount = event.amountFor(current.inputs)
        if (amount < current.receivedAmount ||
            (current.receiptStatus == ReceiptStatus.RECEIVED && event.status == ReceiptStatus.DELAYED)
        ) {
            throw ConflictException("Событие противоречит подтверждённому поступлению")
        }
        val updated = if (current.receiptStatus == event.status && current.receivedAmount == amount) {
            current
        } else {
            current.copy(version = current.version + 1L, receiptStatus = event.status, receivedAmount = amount).also {
                plans.save(it, caller.userId)
            }
        }
        plans.saveEvent(account.id, event, updated.version, caller.userId)
        return updated.quote(account, now)
    }

    @Transactional
    fun confirm(loanId: UUID, expectedVersion: Long): PaymentReserveQuote {
        val (caller, companyId) = CurrentCaller.requireClient()
        val account = lockOwnedAccount(loanId, companyId)
        val current = plans.find(account.id) ?: throw missingReserve()
        val now = today()
        if (current.confirmedDraw?.version == expectedVersion) return current.quote(account, now)
        if (current.version != expectedVersion) {
            throw ConflictException("План изменился. Обновите расчёт перед подтверждением")
        }
        val quote = current.quote(account, now)
        if (!quote.canDraw) {
            throw ConflictException("По текущему плану выборка недоступна. Проверьте поступление, срок и остаток лимита")
        }

        // The outer transaction holds the same account lock used by ordinary tranche requests.
        // The new tranche and its confirmation record both commit, or both roll back.
        val updatedAccount = servicing.drawTranche(account.id, quote.suggestedDraw, DRAW_PURPOSE)
        val tranche = updatedAccount.tranches.last()
        val updated = current.copy(
            version = current.version + 1L,
            confirmedDraw = ConfirmedReserveDraw(expectedVersion, tranche.id, tranche.amount, caller.userId, tranche.disbursedAt),
        )
        plans.save(updated, caller.userId)
        return updated.quote(updatedAccount, now)
    }

    private fun readOwnedAccount(loanId: UUID, companyId: UUID): LoanAccount {
        requireDemo()
        return requireSyntheticOwner(loans.findById(loanId), companyId)
    }

    private fun lockOwnedAccount(loanId: UUID, companyId: UUID): LoanAccount {
        requireDemo()
        return requireSyntheticOwner(loans.findByIdForUpdate(loanId), companyId)
    }

    private fun requireSyntheticOwner(account: LoanAccount?, companyId: UUID): LoanAccount {
        if (account?.companyId != companyId) throw missingReserve()
        val inn = applications.find(account.applicationId)?.company?.inn
        if (inn?.startsWith(SYNTHETIC_INN_PREFIX) != true) throw missingReserve()
        return account
    }

    private fun requireDemo() {
        if (!demoAccounts) throw missingReserve()
    }

    private fun today(): LocalDate = clock.instant().atZone(LOCAL_ZONE).toLocalDate()

    private fun missingReserve() = NotFoundException("Резерв к платежу не найден")

    private companion object {
        const val SYNTHETIC_INN_PREFIX = "00"
        const val DRAW_PURPOSE = "Резерв к дате платежа"
        val LOCAL_ZONE: ZoneId = ZoneId.of("Europe/Moscow")
    }
}
