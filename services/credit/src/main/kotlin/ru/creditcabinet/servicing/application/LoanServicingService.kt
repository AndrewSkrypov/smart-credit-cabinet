package ru.creditcabinet.servicing.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.service.ApplicationService
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.offer.Loan
import ru.creditcabinet.servicing.domain.LoanAccount
import ru.creditcabinet.servicing.domain.LoanAccountStore
import ru.creditcabinet.servicing.domain.LoanOperationReceipt
import java.security.MessageDigest
import java.time.Clock
import java.time.ZoneId
import java.util.HexFormat
import java.util.UUID

@Service
class LoanServicingService(
    private val store: LoanAccountStore,
    private val applications: ApplicationService,
    private val clock: Clock,
) {
    /** Creates the loan account, initial tranche, and payment schedule once upon DISBURSED. */
    @Transactional
    fun openOnDisbursement(application: LoanApplication, loan: Loan): LoanAccount {
        store.findByApplicationId(application.id)?.let { return it }
        val opened = LoanAccount.open(
            applicationId = application.id,
            companyId = application.company.id,
            contractNumber = loan.contractNumber,
            approvedLimit = loan.amount,
            termMonths = loan.termMonths,
            rate = loan.rate,
            revolving = loan.revolving,
            disbursedAt = loan.disbursedAt,
            maturityDate = loan.maturityDate,
            firstPaymentBaseDate = loan.disbursedAt.atZone(ZONE).toLocalDate(),
        )
        store.create(opened)
        return opened
    }

    /** Returns the post-disbursement loan account; access is enforced via ApplicationService.get (fail-closed). */
    fun getByApplicationId(applicationId: UUID): LoanAccount {
        applications.get(applicationId)
        return store.findByApplicationId(applicationId)
            ?: throw NotFoundException("Кредитный счёт по заявке не найден")
    }

    @Transactional
    fun drawTranche(loanId: UUID, key: UUID, amount: Long, purpose: String?): LoanAccount =
        applyOnce(loanId, key, "tranche:$amount:${purpose?.trim().orEmpty()}") { account ->
            val now = clock.instant()
            account.drawTranche(amount, purpose, now, now.atZone(ZONE).toLocalDate())
        }

    @Transactional
    fun payNext(loanId: UUID, key: UUID, paymentId: UUID): LoanAccount =
        applyOnce(loanId, key, "payment:$paymentId") { account ->
            account.payNext(paymentId, clock.instant())
        }

    private fun applyOnce(loanId: UUID, key: UUID, request: String, change: (LoanAccount) -> LoanAccount): LoanAccount {
        val (_, companyId) = CurrentCaller.requireClient()
        val account = ownForUpdate(loanId, companyId)
        val fingerprint = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(request.toByteArray()))
        store.findOperation(loanId, key)?.let { receipt ->
            if (receipt.fingerprint != fingerprint) {
                throw ConflictException("Этот ключ уже использован для другой операции по кредиту")
            }
            return receipt.result
        }
        val updated = change(account)
        val newTranche = if (updated.tranches.size > account.tranches.size) updated.tranches.last() else null
        store.saveState(updated, newTranche)
        store.recordOperation(loanId, key, LoanOperationReceipt(fingerprint, updated))
        return updated
    }

    @Transactional
    fun repayEarly(loanId: UUID): LoanAccount {
        val (_, companyId) = CurrentCaller.requireClient()
        val account = ownForUpdate(loanId, companyId)
        val now = clock.instant()
        val updated = account.repayEarly(now, now.atZone(ZONE).toLocalDate())
        store.saveState(updated)
        return updated
    }

    private fun ownForUpdate(loanId: UUID, companyId: UUID): LoanAccount =
        store.findByIdForUpdate(loanId, companyId)
            ?: throw NotFoundException("Кредитный счёт не найден")

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Moscow")
    }
}
