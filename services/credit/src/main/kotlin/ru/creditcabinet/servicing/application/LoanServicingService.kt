package ru.creditcabinet.servicing.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.service.ApplicationService
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.offer.Loan
import ru.creditcabinet.servicing.domain.LoanAccount
import ru.creditcabinet.servicing.domain.LoanAccountStore
import ru.creditcabinet.servicing.domain.LoanClosureCertificate
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
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
    fun drawTranche(loanId: UUID, amount: Long, purpose: String?): LoanAccount {
        val (_, companyId) = CurrentCaller.requireClient()
        val account = ownForUpdate(loanId, companyId)
        val now = clock.instant()
        val updated = account.drawTranche(amount, purpose, now, now.atZone(ZONE).toLocalDate())
        store.saveState(updated, newTranche = updated.tranches.last())
        return updated
    }

    @Transactional
    fun payNext(loanId: UUID): LoanAccount {
        val (_, companyId) = CurrentCaller.requireClient()
        val account = ownForUpdate(loanId, companyId)
        val updated = account.payNext(clock.instant())
        store.saveState(updated)
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

    @Transactional
    fun closureCertificate(loanId: UUID): LoanClosureCertificate {
        val (_, companyId) = CurrentCaller.requireClient()
        val account = ownForUpdate(loanId, companyId)
        return account.closureCertificate(clock.instant())
    }

    fun today(): LocalDate = clock.instant().atZone(ZONE).toLocalDate()

    private fun ownForUpdate(loanId: UUID, companyId: UUID): LoanAccount =
        store.findByIdForUpdate(loanId)?.takeIf { it.companyId == companyId }
            ?: throw NotFoundException("Кредитный счёт не найден")

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Moscow")
    }
}
