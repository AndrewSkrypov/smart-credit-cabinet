@file:Suppress("ktlint:standard:no-wildcard-imports")

package ru.creditcabinet.servicing.paymentreserve.application

import ru.creditcabinet.agent.application.AgentApplicationService
import ru.creditcabinet.application.application.CreditCaller
import ru.creditcabinet.application.application.CreditFailure.NotFound
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.servicing.contract.v1.*
import ru.creditcabinet.servicing.domain.LoanAccount
import ru.creditcabinet.servicing.paymentreserve.domain.*
import java.time.Clock
import java.util.UUID

/** Demo visibility and mandates stay in Credit; all money mutations go to their owner. */
class PaymentReserveService(
    private val operations: ServicingOperations,
    private val applications: ApplicationRepository,
    private val agentApplications: AgentApplicationService,
    private val clock: Clock,
    private val simulationEnabled: Boolean,
    private val callerContext: CreditCaller,
) {
    fun get(loanId: UUID): PaymentReserveQuote? {
        owned(loanId)
        return operations.reserve(loanId)
    }
    fun put(loanId: UUID, inputs: PaymentReserveInputs): PaymentReserveQuote = operations.putReserve(owned(loanId).id, ReserveCommand(actor(), inputs))
    fun receipt(loanId: UUID, event: ReceiptEvent): PaymentReserveQuote = operations.receipt(owned(loanId).id, ReceiptCommand(actor(), event))
    fun confirm(loanId: UUID, expectedVersion: Long): PaymentReserveQuote =
        operations.confirmReserve(owned(loanId).id, ConfirmReserveCommand(actor(), expectedVersion))
    fun agentSummary(applicationId: UUID): AgentPaymentReserveSummary? {
        requireSimulation()
        val (_, application) = agentApplications.ownAgentApplication(applicationId)
        if (application.id != applicationId || !application.company.inn.startsWith("00")) throw missing()
        if (application.status != ApplicationStatus.DISBURSED) return null
        return operations.agentSummary(applicationId)
    }
    private fun owned(id: UUID): LoanAccount {
        requireSimulation()
        val current = actor()
        val account = operations.byId(id)?.takeIf { it.companyId == current.companyId } ?: throw missing()
        val application = applications.find(account.applicationId) ?: throw missing()
        if (account.id != id ||
            application.id != account.applicationId ||
            application.company.id != current.companyId ||
            application.status != ApplicationStatus.DISBURSED ||
            !application.company.inn.startsWith("00")
        ) throw missing()
        return account
    }
    private fun actor(): ServicingActor {
        val (caller, companyId) = callerContext.requireClient()
        return ServicingActor(caller.userId, companyId)
    }
    private fun requireSimulation() {
        if (!simulationEnabled) throw missing()
    }
    private fun missing() = NotFound("Резерв к платежу не найден")
}
