package ru.creditcabinet.servicing.contract.v1

import ru.creditcabinet.servicing.domain.LoanAccount
import ru.creditcabinet.servicing.paymentreserve.domain.PaymentReserveInputs
import ru.creditcabinet.servicing.paymentreserve.domain.PaymentReserveQuote
import ru.creditcabinet.servicing.paymentreserve.domain.ReceiptEvent
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** v1 is additive. A cycle is the immutable accepted offer id, not the mutable JPA version. */
data class OpenLoanCommand(
    val commandId: UUID,
    val applicationId: UUID,
    val cycleId: UUID,
    val applicationVersion: Long,
    val companyId: UUID,
    val contractSnapshotId: UUID,
    val contractSha256: String,
    val contractNumber: String,
    val approvedLimit: Long,
    val termMonths: Int,
    val rate: BigDecimal,
    val revolving: Boolean,
    val disbursedAt: Instant,
    val maturityDate: LocalDate,
    val currency: String = "RUB",
    val schemaVersion: Int = 1,
) {
    init {
        require(schemaVersion == 1 && currency == "RUB")
        require(applicationVersion >= 0 && approvedLimit > 0 && termMonths > 0 && rate.signum() >= 0)
        require(contractSha256.matches(Regex("[a-f0-9]{64}")))
        require(contractNumber.isNotBlank() && contractNumber.length <= 32)
        require(maturityDate >= disbursedAt.atZone(java.time.ZoneId.of("Europe/Moscow")).toLocalDate())
    }
}

data class LoanOpenedReceipt(
    val commandId: UUID,
    val applicationId: UUID,
    val cycleId: UUID,
    val applicationVersion: Long,
    val accountId: UUID,
    val payloadSha256: String,
    val recordedAt: Instant,
    val schemaVersion: Int = 1,
)

data class ServicingActor(val userId: UUID, val companyId: UUID)

/** [operationKey] makes a retried request return the first result instead of drawing twice. */
data class DrawCommand(val actor: ServicingActor, val amount: Long, val purpose: String? = null, val operationKey: UUID? = null)

/** The client pays the exact payment it saw; [operationKey] makes a retry safe. */
data class PayNextCommand(val actor: ServicingActor, val paymentId: UUID? = null, val operationKey: UUID? = null)
data class ReserveCommand(val actor: ServicingActor, val inputs: PaymentReserveInputs)
data class ReceiptCommand(val actor: ServicingActor, val event: ReceiptEvent)
data class ConfirmReserveCommand(val actor: ServicingActor, val expectedVersion: Long)

/** Authorization for application/agent reads stays at Credit; mutations also enforce company here. */
interface ServicingOperations {
    fun open(command: OpenLoanCommand): LoanOpenedReceipt
    fun byApplication(applicationId: UUID): LoanAccount?
    fun byId(loanId: UUID): LoanAccount?
    fun draw(loanId: UUID, command: DrawCommand): LoanAccount
    fun payNext(loanId: UUID, command: PayNextCommand): LoanAccount
    fun repayEarly(loanId: UUID, actor: ServicingActor): LoanAccount
    fun agentSummary(applicationId: UUID): ru.creditcabinet.servicing.paymentreserve.domain.AgentPaymentReserveSummary?
    fun reserve(loanId: UUID): PaymentReserveQuote?
    fun putReserve(loanId: UUID, command: ReserveCommand): PaymentReserveQuote
    fun receipt(loanId: UUID, command: ReceiptCommand): PaymentReserveQuote
    fun confirmReserve(loanId: UUID, command: ConfirmReserveCommand): PaymentReserveQuote
}
