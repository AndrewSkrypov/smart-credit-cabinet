package ru.creditcabinet.servicing.application

import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.service.ApplicationService
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.offer.Loan
import ru.creditcabinet.servicing.contract.v1.DrawCommand
import ru.creditcabinet.servicing.contract.v1.ServicingActor
import ru.creditcabinet.servicing.contract.v1.ServicingOperations
import ru.creditcabinet.servicing.domain.LoanAccount
import java.util.UUID

/** Credit authorization facade. Transactions and transport are infrastructure ports. */
class LoanServicingService(
    private val operations: ServicingOperations,
    private val applications: ApplicationService,
    private val legacyOpening: (LoanApplication, Loan) -> LoanAccount,
) {
    fun openOnDisbursement(application: LoanApplication, loan: Loan): LoanAccount = legacyOpening(application, loan)
    fun getByApplicationId(applicationId: UUID): LoanAccount {
        applications.get(applicationId)
        return operations.byApplication(applicationId) ?: throw NotFoundException("Кредитный счёт по заявке не найден")
    }
    fun drawTranche(loanId: UUID, amount: Long, purpose: String?): LoanAccount = operations.draw(loanId, DrawCommand(actor(), amount, purpose))
    fun payNext(loanId: UUID): LoanAccount = operations.payNext(loanId, actor())
    fun repayEarly(loanId: UUID): LoanAccount = operations.repayEarly(loanId, actor())
    private fun actor(): ServicingActor {
        val (caller, companyId) = CurrentCaller.requireClient()
        return ServicingActor(caller.userId, companyId)
    }
}
