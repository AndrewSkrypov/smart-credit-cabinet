package ru.creditcabinet.servicing.paymentreserve.domain

import ru.creditcabinet.servicing.domain.LoanAccount
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import ru.creditcabinet.servicing.domain.ServicingBadRequest as BadRequestException

data class PaymentReserveInputs(
    val openingBalance: Long,
    val expectedReceiptAmount: Long,
    val payableAmount: Long,
    val targetBuffer: Long,
    val controlDate: LocalDate,
    val paymentDate: LocalDate,
) {
    init {
        if (openingBalance !in 0L..MAX_RUBLES ||
            expectedReceiptAmount !in 1L..MAX_RUBLES ||
            payableAmount !in 1L..MAX_RUBLES ||
            targetBuffer !in 0L..MAX_RUBLES
        ) {
            throw BadRequestException("Проверьте суммы в рублях: поступление и платёж должны быть положительными")
        }
        if (controlDate > paymentDate) {
            throw BadRequestException("Дата проверки поступления должна быть не позднее даты платежа")
        }
    }

    private companion object {
        const val MAX_RUBLES = 999_999_999_999L
    }
}

enum class ReceiptStatus {
    PENDING,
    RECEIVED,
    DELAYED,
}

data class ReceiptEvent(
    val eventId: String,
    val status: ReceiptStatus,
    val receivedAmount: Long? = null,
) {
    init {
        if (!EVENT_ID.matches(eventId)) throw BadRequestException("Некорректный идентификатор события")
        if (status == ReceiptStatus.PENDING || (receivedAmount != null && receivedAmount < 0L)) {
            throw BadRequestException("Укажите факт поступления или задержки и сумму зачисления")
        }
    }

    fun amountFor(inputs: PaymentReserveInputs): Long {
        val amount = receivedAmount ?: if (status == ReceiptStatus.RECEIVED) inputs.expectedReceiptAmount else 0L
        if (amount > inputs.expectedReceiptAmount || (status == ReceiptStatus.RECEIVED && amount == 0L)) {
            throw BadRequestException("Сумма зачисления должна соответствовать ожидаемому поступлению")
        }
        return amount
    }

    private companion object {
        val EVENT_ID = Regex("[A-Za-z0-9_.:-]{1,128}")
    }
}

data class ConfirmedReserveDraw(
    val version: Long,
    val trancheId: UUID,
    val amount: Long,
    val confirmedBy: UUID,
    val confirmedAt: Instant,
)

data class PaymentReservePlan(
    val loanId: UUID,
    val version: Long,
    val inputs: PaymentReserveInputs,
    val receiptStatus: ReceiptStatus = ReceiptStatus.PENDING,
    val receivedAmount: Long = 0L,
    val confirmedDraw: ConfirmedReserveDraw? = null,
) {
    fun quote(account: LoanAccount, today: LocalDate): PaymentReserveQuote {
        val drawnAmount = confirmedDraw?.amount ?: 0L
        val balance = inputs.openingBalance + receivedAmount + drawnAmount - inputs.payableAmount
        val required = (inputs.targetBuffer - balance).coerceAtLeast(0L)
        val available = account.availableLimit
        val suggested = minOf(required, available)
        val canDraw = receiptStatus == ReceiptStatus.DELAYED &&
            confirmedDraw == null &&
            required > 0L &&
            suggested == required &&
            today <= inputs.paymentDate &&
            today <= account.maturityDate
        return PaymentReserveQuote(
            version = version,
            inputs = inputs,
            receiptStatus = receiptStatus,
            receivedAmount = receivedAmount,
            remainingLimit = available,
            requiredAmount = required,
            suggestedDraw = suggested,
            shortfall = required - suggested,
            projectedBalance = balance,
            canDraw = canDraw,
            drawnAmount = drawnAmount,
        )
    }
}

data class PaymentReserveQuote(
    val version: Long,
    val inputs: PaymentReserveInputs,
    val receiptStatus: ReceiptStatus,
    val receivedAmount: Long,
    val remainingLimit: Long,
    val requiredAmount: Long,
    val suggestedDraw: Long,
    val shortfall: Long,
    val projectedBalance: Long,
    val canDraw: Boolean,
    val drawnAmount: Long,
) {
    fun agentSummary(today: LocalDate): AgentPaymentReserveSummary {
        val action = when {
            drawnAmount > 0L -> AgentReserveAction.TRANCHE_CONFIRMED
            today > inputs.paymentDate -> AgentReserveAction.REVIEW_REQUIRED
            receiptStatus == ReceiptStatus.PENDING -> AgentReserveAction.WAIT_FOR_RECEIPT
            requiredAmount == 0L -> AgentReserveAction.NO_FINANCING_NEEDED
            canDraw -> AgentReserveAction.CLIENT_CONFIRM_DRAW
            else -> AgentReserveAction.REVIEW_REQUIRED
        }
        return AgentPaymentReserveSummary(version, receiptStatus, inputs.paymentDate, action)
    }
}

enum class AgentReserveAction {
    WAIT_FOR_RECEIPT,
    NO_FINANCING_NEEDED,
    CLIENT_CONFIRM_DRAW,
    REVIEW_REQUIRED,
    TRANCHE_CONFIRMED,
}

data class AgentPaymentReserveSummary(
    val version: Long,
    val receiptStatus: ReceiptStatus,
    val paymentDate: LocalDate,
    val action: AgentReserveAction,
)
