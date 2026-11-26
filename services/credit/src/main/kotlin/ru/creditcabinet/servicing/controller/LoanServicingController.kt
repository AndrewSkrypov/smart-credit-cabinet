package ru.creditcabinet.servicing.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.servicing.application.LoanServicingService
import ru.creditcabinet.servicing.domain.LoanAccount
import ru.creditcabinet.servicing.domain.LoanAccountStatus
import ru.creditcabinet.servicing.domain.LoanClosureCertificate
import ru.creditcabinet.servicing.domain.LoanPayment
import ru.creditcabinet.servicing.domain.LoanPaymentStatus
import ru.creditcabinet.servicing.domain.LoanTranche
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class DrawTrancheRequest(
    val amount: Long,
    val purpose: String? = null,
)

data class LoanTrancheResponse(
    val id: UUID,
    val trancheNumber: Int,
    val amount: Long,
    val purpose: String?,
    val disbursedAt: Instant,
)

data class LoanPaymentResponse(
    val id: UUID,
    val paymentNumber: Int,
    val dueDate: LocalDate,
    val paymentAmount: BigDecimal,
    val principalAmount: BigDecimal,
    val interestAmount: BigDecimal,
    val remainingBalance: BigDecimal,
    val status: LoanPaymentStatus,
    val paidAt: Instant?,
    val overdue: Boolean = false,
)

data class LoanAccountResponse(
    val id: UUID,
    val applicationId: UUID,
    val contractNumber: String,
    val status: LoanAccountStatus,
    val approvedLimit: Long,
    val disbursedAmount: Long,
    val availableLimit: Long,
    val outstandingPrincipal: BigDecimal,
    val rate: BigDecimal,
    val termMonths: Int,
    val revolving: Boolean,
    val disbursedAt: Instant,
    val maturityDate: LocalDate,
    val repaidAt: Instant?,
    val hasOverduePayments: Boolean = false,
    val overdueDays: Long = 0L,
    val overdueAmount: BigDecimal = BigDecimal.ZERO,
    val tranches: List<LoanTrancheResponse>,
    val payments: List<LoanPaymentResponse>,
)

@Tag(name = "Обслуживание кредита и транши")
@RestController
class LoanServicingController(
    private val servicing: LoanServicingService,
) {
    @Operation(summary = "Кредитный счёт, транши и график платежей по выданной заявке")
    @GetMapping("/api/v1/applications/{id}/loan")
    fun getByApplication(@PathVariable id: UUID): LoanAccountResponse =
        servicing.getByApplicationId(id).toResponse()

    @Operation(summary = "Кредитный счёт и транши по выданной агентской заявке (только чтение)")
    @GetMapping("/api/v1/agent/applications/{id}/loan")
    fun getByAgentApplication(@PathVariable id: UUID): LoanAccountResponse {
        CurrentCaller.requireRole(Roles.AGENT)
        return servicing.getByApplicationId(id).toResponse()
    }

    @Operation(summary = "Выбрать очередной транш по возобновляемому кредиту (только клиент)")
    @PostMapping("/api/v1/loans/{loanId}/tranches")
    fun drawTranche(@PathVariable loanId: UUID, @RequestBody request: DrawTrancheRequest): LoanAccountResponse =
        servicing.drawTranche(loanId, request.amount, request.purpose).toResponse()

    @Operation(summary = "Провести очередной плановый платёж по кредиту (только клиент)")
    @PostMapping("/api/v1/loans/{loanId}/payments/next")
    fun payNext(@PathVariable loanId: UUID): LoanAccountResponse =
        servicing.payNext(loanId).toResponse()

    @Operation(summary = "Досрочно погасить весь остаток долга по кредиту (только клиент)")
    @PostMapping("/api/v1/loans/{loanId}/repay-early")
    fun repayEarly(@PathVariable loanId: UUID): LoanAccountResponse =
        servicing.repayEarly(loanId).toResponse()

    @Operation(summary = "Справка о полном погашении кредитного договора (только клиент, после REPAID)")
    @GetMapping("/api/v1/loans/{loanId}/closure-certificate")
    fun closureCertificate(@PathVariable loanId: UUID): LoanClosureCertificate =
        servicing.closureCertificate(loanId)

    private fun LoanAccount.toResponse(): LoanAccountResponse {
        val today = servicing.today()
        return LoanAccountResponse(
            id = id,
            applicationId = applicationId,
            contractNumber = contractNumber,
            status = status,
            approvedLimit = approvedLimit,
            disbursedAmount = disbursedAmount,
            availableLimit = availableLimit,
            outstandingPrincipal = outstandingPrincipal,
            rate = rate,
            termMonths = termMonths,
            revolving = revolving,
            disbursedAt = disbursedAt,
            maturityDate = maturityDate,
            repaidAt = repaidAt,
            hasOverduePayments = hasOverduePayments(today),
            overdueDays = overdueDays(today),
            overdueAmount = overdueAmount(today),
            tranches = tranches.map { it.toResponse() },
            payments = payments.map { payment ->
                val isOverdue = status == LoanAccountStatus.ACTIVE &&
                    payment.status == LoanPaymentStatus.SCHEDULED &&
                    payment.dueDate.isBefore(today)
                payment.toResponse(isOverdue)
            },
        )
    }

    private fun LoanTranche.toResponse() = LoanTrancheResponse(
        id = id,
        trancheNumber = trancheNumber,
        amount = amount,
        purpose = purpose,
        disbursedAt = disbursedAt,
    )

    private fun LoanPayment.toResponse(overdue: Boolean) = LoanPaymentResponse(
        id = id,
        paymentNumber = paymentNumber,
        dueDate = dueDate,
        paymentAmount = paymentAmount,
        principalAmount = principalAmount,
        interestAmount = interestAmount,
        remainingBalance = remainingBalance,
        status = status,
        paidAt = paidAt,
        overdue = overdue,
    )
}
