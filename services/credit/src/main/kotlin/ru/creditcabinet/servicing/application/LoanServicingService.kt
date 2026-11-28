package ru.creditcabinet.servicing.application

import ru.creditcabinet.application.application.CreditCaller
import ru.creditcabinet.application.application.CreditFailure.NotFound
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.service.ApplicationService
import ru.creditcabinet.offer.Loan
import ru.creditcabinet.servicing.contract.v1.DrawCommand
import ru.creditcabinet.servicing.contract.v1.PayNextCommand
import ru.creditcabinet.servicing.contract.v1.ServicingActor
import ru.creditcabinet.servicing.contract.v1.ServicingOperations
import ru.creditcabinet.servicing.domain.LoanAccount
import ru.creditcabinet.servicing.domain.LoanClosureCertificate
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Credit authorization facade. Transactions and transport are infrastructure ports. */
class LoanServicingService(
    private val operations: ServicingOperations,
    private val applications: ApplicationService,
    private val callerContext: CreditCaller,
    private val clock: Clock = Clock.systemUTC(),
    private val legacyOpening: (LoanApplication, Loan) -> LoanAccount,
) {
    fun openOnDisbursement(application: LoanApplication, loan: Loan): LoanAccount = legacyOpening(application, loan)
    fun getByApplicationId(applicationId: UUID): LoanAccount {
        val application = applications.get(applicationId)
        if (application.status != ApplicationStatus.DISBURSED) throw missing()
        return operations.byApplication(applicationId) ?: throw NotFound("Кредитный счёт по заявке не найден")
    }
    fun drawTranche(loanId: UUID, key: UUID, amount: Long, purpose: String?): LoanAccount =
        operations.draw(loanId, DrawCommand(readyActor(loanId), amount, purpose, operationKey = key))
    fun payNext(loanId: UUID, key: UUID, paymentId: UUID): LoanAccount =
        operations.payNext(loanId, PayNextCommand(readyActor(loanId), paymentId = paymentId, operationKey = key))
    fun repayEarly(loanId: UUID): LoanAccount = operations.repayEarly(loanId, readyActor(loanId))
    private fun readyActor(loanId: UUID): ServicingActor {
        val actor = actor()
        val account = operations.byId(loanId)?.takeIf { it.companyId == actor.companyId } ?: throw missing()
        if (applications.get(account.applicationId).status != ApplicationStatus.DISBURSED) throw missing()
        return actor
    }
    private fun missing() = NotFound("Кредитный счёт по заявке не найден")
    private fun actor(): ServicingActor {
        val (caller, companyId) = callerContext.requireClient()
        return ServicingActor(caller.userId, companyId)
    }

    /** A paid-off loan's closure certificate; only the client's company may request it. */
    fun closureCertificate(loanId: UUID): LoanClosureCertificate {
        val actor = readyActor(loanId)
        val account = operations.byId(loanId)?.takeIf { it.companyId == actor.companyId } ?: throw missing()
        return account.closureCertificate(clock.instant())
    }

    fun today(): LocalDate = clock.instant().atZone(ZONE).toLocalDate()

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Moscow")
    }
}
