package ru.creditcabinet.agent.controller

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import ru.creditcabinet.agent.domain.AgentPortfolioSummary
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.product.domain.FinancingPurpose
import java.util.UUID

data class AgentCreateApplicationRequest(
    val companyId: UUID,
    @field:NotBlank(message = "Выберите продукт")
    val productCode: String,
    val purpose: FinancingPurpose,
    @field:Size(max = 1000, message = "Комментарий к цели — до 1000 символов")
    val purposeComment: String? = null,
    @field:Positive(message = "Сумма должна быть больше нуля")
    val amount: Long,
    @field:Min(1, message = "Срок — от 1 месяца")
    @field:Max(120, message = "Срок — не больше 120 месяцев")
    val termMonths: Int,
    @field:Positive(message = "Выручка должна быть больше нуля")
    val declaredRevenue12m: Long? = null,
    @field:PositiveOrZero(message = "Платежи по кредитам не могут быть отрицательными")
    val declaredMonthlyDebtPayments: Long? = null,
) {
    fun toTerms() =
        ApplicationTerms(
            productCode = productCode.trim(),
            purpose = purpose,
            purposeComment = purposeComment?.trim()?.takeIf { it.isNotEmpty() },
            amount = amount,
            termMonths = termMonths,
            declaredRevenue12m = declaredRevenue12m,
            declaredMonthlyDebtPayments = declaredMonthlyDebtPayments,
            bureauConsent = false,
        )
}

data class AgentPortfolioResponse(
    val assignedCompaniesCount: Int,
    val totalApplications: Int,
    val draftCount: Int,
    val pendingConfirmationCount: Int,
    val inProgressCount: Int,
    val disbursedCount: Int,
    val rejectedOrClosedCount: Int,
    val expectedCommissionTotal: Long,
    val accruedCommissionTotal: Long,
) {
    companion object {
        fun of(summary: AgentPortfolioSummary) =
            AgentPortfolioResponse(
                assignedCompaniesCount = summary.assignedCompaniesCount,
                totalApplications = summary.totalApplications,
                draftCount = summary.draftCount,
                pendingConfirmationCount = summary.pendingConfirmationCount,
                inProgressCount = summary.inProgressCount,
                disbursedCount = summary.disbursedCount,
                rejectedOrClosedCount = summary.rejectedOrClosedCount,
                expectedCommissionTotal = summary.expectedCommissionTotal,
                accruedCommissionTotal = summary.accruedCommissionTotal,
            )
    }
}
