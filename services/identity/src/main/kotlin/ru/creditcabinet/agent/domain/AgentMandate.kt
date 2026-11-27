package ru.creditcabinet.agent.domain

import ru.creditcabinet.company.domain.Company
import java.time.Instant
import java.util.UUID

enum class AgentMandateStatus { ACTIVE, EXPIRED, REVOKED }

enum class AgentMandateSource { LEGACY, COMPANY }

/**
 * Mandate authorizing a partner agent to manage credit applications on behalf of a client company.
 * Agents never receive signing rights; revocation immediately blocks further access.
 */
data class AgentMandate(
    val id: UUID = UUID.randomUUID(),
    val agentUserId: UUID,
    val companyId: UUID,
    val status: AgentMandateStatus = AgentMandateStatus.ACTIVE,
    val assignedAt: Instant = Instant.now(),
    val revokedAt: Instant? = null,
    val expiresAt: Instant? = null,
    val issuedSource: AgentMandateSource = AgentMandateSource.LEGACY,
    val issuedBy: UUID? = null,
    val revokedSource: AgentMandateSource? = null,
    val revokedBy: UUID? = null,
) {
    fun statusAt(now: Instant): AgentMandateStatus =
        if (status == AgentMandateStatus.ACTIVE && expiresAt?.isAfter(now) == false) AgentMandateStatus.EXPIRED else status

    val isActive: Boolean get() = statusAt(Instant.now()) == AgentMandateStatus.ACTIVE

    fun revoke(at: Instant = Instant.now(), actor: UUID? = null): AgentMandate {
        require(statusAt(at) == AgentMandateStatus.ACTIVE) { "Мандат не действует" }
        return copy(
            status = AgentMandateStatus.REVOKED,
            revokedAt = at,
            revokedSource = if (actor == null) AgentMandateSource.LEGACY else AgentMandateSource.COMPANY,
            revokedBy = actor,
        )
    }
}

data class AgentClientEntry(
    val company: Company,
    val assignedAt: Instant,
)

interface AgentClientPort {
    fun listActiveClients(agentUserId: UUID): List<AgentClientEntry>
    fun listActiveCompanyIds(agentUserId: UUID): List<UUID>
    fun findActiveMandate(agentUserId: UUID, companyId: UUID): AgentMandate?
}

data class CompanyAgentMandate(val mandate: AgentMandate, val agentName: String)

interface AgentMandatePort {
    fun listForCompany(companyId: UUID): List<CompanyAgentMandate>
    fun findForCompany(companyId: UUID, mandateId: UUID): AgentMandate?
    fun activeAgentName(agentUserId: UUID): String?
    fun currentGrant(companyId: UUID, agentUserId: UUID): AgentMandate?
    fun insert(mandate: AgentMandate)
    fun markExpired(companyId: UUID, mandateId: UUID)
    fun revoke(mandate: AgentMandate)
}
