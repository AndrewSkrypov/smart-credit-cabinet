package ru.creditcabinet.agent.infrastructure

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import ru.creditcabinet.agent.domain.AgentClientEntry
import ru.creditcabinet.agent.domain.AgentClientPort
import ru.creditcabinet.agent.domain.AgentMandate
import ru.creditcabinet.agent.domain.AgentMandateStatus
import ru.creditcabinet.company.domain.Company
import java.util.UUID

@Component
class AgentClientJdbcStore(
    private val jdbc: NamedParameterJdbcTemplate,
) : AgentClientPort {
    override fun listActiveClients(agentUserId: UUID): List<AgentClientEntry> =
        jdbc.query(
            """
            select c.id, c.inn, c.name, c.created_at, ac.assigned_at
            from agent_clients ac
            join companies c on c.id = ac.company_id
            join users u on u.id = ac.agent_user_id
            where ac.agent_user_id = :agentId
              and ac.status = 'ACTIVE'
              and u.role = 'AGENT'
              and u.status = 'ACTIVE'
            order by ac.assigned_at asc, c.id asc
            """.trimIndent(),
            mapOf("agentId" to agentUserId),
        ) { rs, _ ->
            AgentClientEntry(
                company = Company(
                    id = rs.getObject("id", UUID::class.java),
                    inn = rs.getString("inn"),
                    name = rs.getString("name"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                ),
                assignedAt = rs.getTimestamp("assigned_at").toInstant(),
            )
        }

    override fun listActiveCompanyIds(agentUserId: UUID): List<UUID> =
        jdbc.query(
            """
            select ac.company_id
            from agent_clients ac
            join users u on u.id = ac.agent_user_id
            where ac.agent_user_id = :agentId
              and ac.status = 'ACTIVE'
              and u.role = 'AGENT'
              and u.status = 'ACTIVE'
            order by ac.assigned_at asc, ac.company_id asc
            """.trimIndent(),
            mapOf("agentId" to agentUserId),
        ) { rs, _ -> rs.getObject("company_id", UUID::class.java) }

    override fun findActiveMandate(agentUserId: UUID, companyId: UUID): AgentMandate? =
        jdbc.query(
            """
            select ac.id, ac.agent_user_id, ac.company_id, ac.status, ac.assigned_at, ac.revoked_at
            from agent_clients ac
            join users u on u.id = ac.agent_user_id
            where ac.agent_user_id = :agentId
              and ac.company_id = :companyId
              and ac.status = 'ACTIVE'
              and u.role = 'AGENT'
              and u.status = 'ACTIVE'
            limit 1
            """.trimIndent(),
            mapOf("agentId" to agentUserId, "companyId" to companyId),
        ) { rs, _ ->
            AgentMandate(
                id = rs.getObject("id", UUID::class.java),
                agentUserId = rs.getObject("agent_user_id", UUID::class.java),
                companyId = rs.getObject("company_id", UUID::class.java),
                status = AgentMandateStatus.valueOf(rs.getString("status")),
                assignedAt = rs.getTimestamp("assigned_at").toInstant(),
                revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
            )
        }.firstOrNull()
}
