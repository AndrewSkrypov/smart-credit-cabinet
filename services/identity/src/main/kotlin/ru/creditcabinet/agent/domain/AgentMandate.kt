package ru.creditcabinet.agent.domain

import ru.creditcabinet.company.domain.Company
import java.time.Instant
import java.util.UUID

enum class AgentMandateStatus { ACTIVE, REVOKED }

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
) {
    val isActive: Boolean get() = status == AgentMandateStatus.ACTIVE

    fun revoke(at: Instant = Instant.now()): AgentMandate {
        require(isActive) { "Мандат уже отозван" }
        return copy(status = AgentMandateStatus.REVOKED, revokedAt = at)
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
