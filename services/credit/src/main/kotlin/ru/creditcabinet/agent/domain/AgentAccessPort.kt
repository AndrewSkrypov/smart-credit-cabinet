package ru.creditcabinet.agent.domain

import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.CommissionStatus
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LoanApplication
import java.util.UUID

data class AgentAccessDecision(
    val allowed: Boolean,
    val company: CompanySnapshot? = null,
)

/** Mandate verification against identity: fail-closed (503) when identity is unreachable. */
interface AgentAccessPort {
    fun listCompanyIds(agentId: UUID): List<UUID>
    fun checkAccess(agentId: UUID, companyId: UUID): AgentAccessDecision
}

data class AgentPortfolioSummary(
    val assignedCompaniesCount: Int,
    val totalApplications: Int,
    val draftCount: Int,
    val pendingConfirmationCount: Int,
    val inProgressCount: Int,
    val disbursedCount: Int,
    val rejectedOrClosedCount: Int,
    val expectedCommissionTotal: Long,
    val accruedCommissionTotal: Long,
)

object AgentPortfolioCalculator {
    fun summarize(assignedCompaniesCount: Int, applications: List<LoanApplication>): AgentPortfolioSummary =
        AgentPortfolioSummary(
            assignedCompaniesCount = assignedCompaniesCount,
            totalApplications = applications.size,
            draftCount = applications.count { it.status == ApplicationStatus.DRAFT },
            pendingConfirmationCount = applications.count { it.status == ApplicationStatus.PENDING_CLIENT_CONFIRMATION },
            inProgressCount = applications.count {
                !it.status.terminal &&
                    it.status != ApplicationStatus.DRAFT &&
                    it.status != ApplicationStatus.PENDING_CLIENT_CONFIRMATION
            },
            disbursedCount = applications.count { it.status == ApplicationStatus.DISBURSED },
            rejectedOrClosedCount = applications.count {
                it.status in setOf(ApplicationStatus.REJECTED, ApplicationStatus.WITHDRAWN, ApplicationStatus.CLOSED)
            },
            expectedCommissionTotal = applications
                .filter { it.agentCommissionStatus == CommissionStatus.EXPECTED }
                .sumOf { it.agentCommissionAmount ?: 0L },
            accruedCommissionTotal = applications
                .filter { it.agentCommissionStatus == CommissionStatus.ACCRUED }
                .sumOf { it.agentCommissionAmount ?: 0L },
        )
}

class AgentAccessUnavailable : RuntimeException("Сервис учётных записей недоступен — попробуйте через минуту")
