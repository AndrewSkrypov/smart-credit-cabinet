package ru.creditcabinet.agent.application

import org.springframework.stereotype.Service
import ru.creditcabinet.agent.domain.AgentClientEntry
import ru.creditcabinet.agent.domain.AgentClientPort
import ru.creditcabinet.company.domain.Company
import ru.creditcabinet.company.domain.CompanyDataPort
import java.util.UUID

@Service
class AgentClientService(
    private val agentClients: AgentClientPort,
    private val companies: CompanyDataPort,
) {
    fun listClients(agentUserId: UUID): List<AgentClientEntry> = agentClients.listActiveClients(agentUserId)

    fun listCompanyIds(agentUserId: UUID): List<UUID> = agentClients.listActiveCompanyIds(agentUserId)

    fun checkAccess(agentUserId: UUID, companyId: UUID): Company? {
        val mandate = agentClients.findActiveMandate(agentUserId, companyId)
        if (mandate == null || !mandate.isActive) return null
        return companies.findById(companyId)
    }
}
