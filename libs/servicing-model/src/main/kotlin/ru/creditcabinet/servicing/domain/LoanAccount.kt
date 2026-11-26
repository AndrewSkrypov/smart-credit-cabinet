package ru.creditcabinet.servicing.domain

import ru.creditcabinet.product.domain.LoanMath
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import ru.creditcabinet.servicing.domain.ServicingBadRequest as BadRequestException
import ru.creditcabinet.servicing.domain.ServicingConflict as ConflictException

enum class LoanAccountStatus {
    ACTIVE,
    REPAID,
}

enum class LoanPaymentStatus {
    SCHEDULED,
    PAID,
}

data class LoanTranche(
    val id: UUID = UUID.randomUUID(),
    val loanAccountId: UUID,
    val trancheNumber: Int,
    val amount: Long,
    val purpose: String?,
    val disbursedAt: Instant,
)

data class LoanPayment(
    val id: UUID = UUID.randomUUID(),
    val loanAccountId: UUID,
    val paymentNumber: Int,
    val dueDate: LocalDate,
    val paymentAmount: BigDecimal,
    val principalAmount: BigDecimal,
    val interestAmount: BigDecimal,
    val remainingBalance: BigDecimal,
    val status: LoanPaymentStatus,
    val paidAt: Instant? = null,
)

data class LoanAccount(
    val id: UUID = UUID.randomUUID(),
    val applicationId: UUID,
    val companyId: UUID,
    val contractNumber: String,
    val approvedLimit: Long,
    val disbursedAmount: Long,
    val outstandingPrincipal: BigDecimal,
    val rate: BigDecimal,
    val termMonths: Int,
    val revolving: Boolean,
    val status: LoanAccountStatus = LoanAccountStatus.ACTIVE,
    val disbursedAt: Instant,
    val maturityDate: LocalDate,
    val repaidAt: Instant? = null,
    val tranches: List<LoanTranche> = emptyList(),
    val payments: List<LoanPayment> = emptyList(),
) {
    val availableLimit: Long
        get() = if (revolving && status == LoanAccountStatus.ACTIVE) {
            (approvedLimit - disbursedAmount).coerceAtLeast(0L)
        } else {
            0L
        }

    fun drawTranche(amount: Long, purpose: String?, now: Instant, today: LocalDate): LoanAccount {
        if (amount <= 0L) {
            throw BadRequestException("Сумма транша должна быть больше нуля")
        }
        if (!revolving) {
            throw ConflictException("Потраншевая выдача доступна только для возобновляемых продуктов")
        }
        if (status != LoanAccountStatus.ACTIVE) {
            throw ConflictException("Кредит уже полностью погашен")
        }
        val remainingLimit = approvedLimit - disbursedAmount
        if (amount > remainingLimit) {
            throw ConflictException("Сумма транша превышает доступный остаток лимита ($remainingLimit)")
        }

        val cleanPurpose = purpose?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_PURPOSE_LENGTH)
        val nextNumber = (tranches.maxOfOrNull { it.trancheNumber } ?: 0) + 1
        val newTranche = LoanTranche(
            loanAccountId = id,
            trancheNumber = nextNumber,
            amount = amount,
            purpose = cleanPurpose,
            disbursedAt = now,
        )

        val updatedDisbursed = disbursedAmount + amount
        val updatedOutstanding = outstandingPrincipal.add(BigDecimal(amount)).setScale(2, RoundingMode.HALF_UP)

        val paidPayments = payments.filter { it.status == LoanPaymentStatus.PAID }.sortedBy { it.paymentNumber }
        val existingScheduled = payments.filter { it.status == LoanPaymentStatus.SCHEDULED }.sortedBy { it.paymentNumber }
        val dueDates = if (existingScheduled.isNotEmpty()) {
            existingScheduled.map { it.dueDate }
        } else {
            listOf(today.plusMonths(1))
        }
        val startPaymentNumber = (paidPayments.maxOfOrNull { it.paymentNumber } ?: 0) + 1
        val recalculated = buildSchedule(
            loanAccountId = id,
            principal = updatedOutstanding,
            rate = rate,
            revolving = revolving,
            startPaymentNumber = startPaymentNumber,
            dueDates = dueDates,
        )

        return copy(
            disbursedAmount = updatedDisbursed,
            outstandingPrincipal = updatedOutstanding,
            tranches = tranches + newTranche,
            payments = paidPayments + recalculated,
        )
    }

    fun payNext(now: Instant): LoanAccount {
        if (status != LoanAccountStatus.ACTIVE) {
            throw ConflictException("Кредит уже полностью погашен")
        }
        val ordered = payments.sortedBy { it.paymentNumber }
        val nextIndex = ordered.indexOfFirst { it.status == LoanPaymentStatus.SCHEDULED }
        if (nextIndex < 0) {
            throw ConflictException("Нет запланированных платежей")
        }
        val next = ordered[nextIndex]
        val paidPayment = next.copy(status = LoanPaymentStatus.PAID, paidAt = now)
        val updatedPayments = ordered.toMutableList().apply { this[nextIndex] = paidPayment }
        val remaining = outstandingPrincipal.subtract(next.principalAmount).setScale(2, RoundingMode.HALF_UP)
        val hasMoreScheduled = updatedPayments.any { it.status == LoanPaymentStatus.SCHEDULED }

        return if (!hasMoreScheduled || remaining.compareTo(BigDecimal.ZERO) <= 0) {
            copy(
                outstandingPrincipal = ZERO_MONEY,
                status = LoanAccountStatus.REPAID,
                repaidAt = now,
                payments = updatedPayments.filter { it.status == LoanPaymentStatus.PAID },
            )
        } else {
            copy(
                outstandingPrincipal = remaining,
                payments = updatedPayments,
            )
        }
    }

    fun repayEarly(now: Instant, today: LocalDate): LoanAccount {
        if (status != LoanAccountStatus.ACTIVE || outstandingPrincipal.compareTo(BigDecimal.ZERO) <= 0) {
            throw ConflictException("Кредит уже полностью погашен")
        }
        val paidPayments = payments.filter { it.status == LoanPaymentStatus.PAID }.sortedBy { it.paymentNumber }
        val closingPayment = LoanPayment(
            loanAccountId = id,
            paymentNumber = (paidPayments.maxOfOrNull { it.paymentNumber } ?: 0) + 1,
            dueDate = today,
            paymentAmount = outstandingPrincipal.setScale(2, RoundingMode.HALF_UP),
            principalAmount = outstandingPrincipal.setScale(2, RoundingMode.HALF_UP),
            interestAmount = ZERO_MONEY,
            remainingBalance = ZERO_MONEY,
            status = LoanPaymentStatus.PAID,
            paidAt = now,
        )
        return copy(
            outstandingPrincipal = ZERO_MONEY,
            status = LoanAccountStatus.REPAID,
            repaidAt = now,
            payments = paidPayments + closingPayment,
        )
    }

    companion object {
        private const val MAX_PURPOSE_LENGTH = 500
        private val ZERO_MONEY: BigDecimal = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP)

        fun initialTrancheAmount(approvedLimit: Long, revolving: Boolean): Long {
            require(approvedLimit > 0L) { "Одобренный лимит должен быть больше нуля" }
            return if (revolving) (approvedLimit / 2L).coerceAtLeast(1L) else approvedLimit
        }

        fun open(
            applicationId: UUID,
            companyId: UUID,
            contractNumber: String,
            approvedLimit: Long,
            termMonths: Int,
            rate: BigDecimal,
            revolving: Boolean,
            disbursedAt: Instant,
            maturityDate: LocalDate,
            firstPaymentBaseDate: LocalDate,
        ): LoanAccount {
            require(termMonths > 0) { "Срок кредита должен быть больше нуля" }
            val accountId = UUID.randomUUID()
            val initialAmount = initialTrancheAmount(approvedLimit, revolving)
            val initialPrincipal = BigDecimal(initialAmount).setScale(2, RoundingMode.HALF_UP)
            val initialTranche = LoanTranche(
                loanAccountId = accountId,
                trancheNumber = 1,
                amount = initialAmount,
                purpose = if (revolving) "Первоначальный транш по кредитной линии" else "Выдача кредита",
                disbursedAt = disbursedAt,
            )
            val dueDates = (1..termMonths).map { month -> firstPaymentBaseDate.plusMonths(month.toLong()) }
            val schedule = buildSchedule(
                loanAccountId = accountId,
                principal = initialPrincipal,
                rate = rate,
                revolving = revolving,
                startPaymentNumber = 1,
                dueDates = dueDates,
            )
            return LoanAccount(
                id = accountId,
                applicationId = applicationId,
                companyId = companyId,
                contractNumber = contractNumber,
                approvedLimit = approvedLimit,
                disbursedAmount = initialAmount,
                outstandingPrincipal = initialPrincipal,
                rate = rate,
                termMonths = termMonths,
                revolving = revolving,
                status = LoanAccountStatus.ACTIVE,
                disbursedAt = disbursedAt,
                maturityDate = maturityDate,
                repaidAt = null,
                tranches = listOf(initialTranche),
                payments = schedule,
            )
        }

        private fun buildSchedule(
            loanAccountId: UUID,
            principal: BigDecimal,
            rate: BigDecimal,
            revolving: Boolean,
            startPaymentNumber: Int,
            dueDates: List<LocalDate>,
        ): List<LoanPayment> {
            val months = dueDates.size
            val baseAmount = principal.setScale(0, RoundingMode.HALF_UP).toLong().coerceAtLeast(1L)
            val calculated = LoanMath.calculate(baseAmount, rate, months, revolving)
            var balance = principal.setScale(2, RoundingMode.HALF_UP)

            return calculated.schedule.mapIndexed { index, row ->
                val isLast = index == months - 1
                val principalPart = if (isLast) {
                    balance
                } else {
                    row.principal.min(balance).setScale(2, RoundingMode.HALF_UP)
                }
                balance = balance.subtract(principalPart).setScale(2, RoundingMode.HALF_UP)
                val interestPart = row.interest.setScale(2, RoundingMode.HALF_UP)
                val paymentPart = principalPart.add(interestPart).setScale(2, RoundingMode.HALF_UP)
                LoanPayment(
                    loanAccountId = loanAccountId,
                    paymentNumber = startPaymentNumber + index,
                    dueDate = dueDates[index],
                    paymentAmount = paymentPart,
                    principalAmount = principalPart,
                    interestAmount = interestPart,
                    remainingBalance = balance,
                    status = LoanPaymentStatus.SCHEDULED,
                    paidAt = null,
                )
            }
        }
    }
}
