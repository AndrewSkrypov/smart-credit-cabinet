package ru.creditcabinet.agent.infrastructure

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import ru.creditcabinet.agent.domain.AgentClientEntry
import ru.creditcabinet.agent.domain.AgentClientPort
import ru.creditcabinet.agent.domain.AgentMandate
import ru.creditcabinet.agent.domain.AgentMandatePort
import ru.creditcabinet.agent.domain.AgentMandateSource
import ru.creditcabinet.agent.domain.AgentMandateStatus
import ru.creditcabinet.agent.domain.CompanyAgentMandate
import ru.creditcabinet.company.domain.Company
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

@Component
class AgentClientJdbcStore(
    private val jdbc: NamedParameterJdbcTemplate,
) : AgentClientPort, AgentMandatePort {
    override fun listActiveClients(agentUserId: UUID): List<AgentClientEntry> =
        jdbc.query(
            """
            select c.id, c.inn, c.name, c.created_at, ac.assigned_at
            from agent_clients ac
            join companies c on c.id = ac.company_id
            join users u on u.id = ac.agent_user_id
            where ac.agent_user_id = :agentId
              and ac.status = 'ACTIVE'
              and (ac.expires_at is null or ac.expires_at > statement_timestamp())
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
              and (ac.expires_at is null or ac.expires_at > statement_timestamp())
              and u.role = 'AGENT'
              and u.status = 'ACTIVE'
            order by ac.assigned_at asc, ac.company_id asc
            """.trimIndent(),
            mapOf("agentId" to agentUserId),
        ) { rs, _ -> rs.getObject("company_id", UUID::class.java) }

    override fun findActiveMandate(agentUserId: UUID, companyId: UUID): AgentMandate? =
        jdbc.query(
            """
            select ac.*
            from agent_clients ac
            join users u on u.id = ac.agent_user_id
            where ac.agent_user_id = :agentId
              and ac.company_id = :companyId
              and ac.status = 'ACTIVE'
              and (ac.expires_at is null or ac.expires_at > statement_timestamp())
              and u.role = 'AGENT'
              and u.status = 'ACTIVE'
            limit 1
            """.trimIndent(),
            mapOf("agentId" to agentUserId, "companyId" to companyId),
        ) { rs, _ -> mandate(rs) }.firstOrNull()

    override fun listForCompany(companyId: UUID): List<CompanyAgentMandate> = jdbc.query(
        """
        select ac.*, u.full_name
        from agent_clients ac join users u on u.id = ac.agent_user_id
        where ac.company_id = :companyId
        order by ac.assigned_at desc, ac.id desc
        """.trimIndent(),
        mapOf("companyId" to companyId),
    ) { rs, _ -> CompanyAgentMandate(mandate(rs), rs.getString("full_name")) }

    override fun findForCompany(companyId: UUID, mandateId: UUID): AgentMandate? = jdbc.query(
        "select * from agent_clients where company_id = :companyId and id = :mandateId",
        mapOf("companyId" to companyId, "mandateId" to mandateId),
    ) { rs, _ -> mandate(rs) }.firstOrNull()

    override fun activeAgentName(agentUserId: UUID): String? = jdbc.query(
        "select full_name from users where id = :agentId and role = 'AGENT' and status = 'ACTIVE' for share",
        mapOf("agentId" to agentUserId),
    ) { rs, _ -> rs.getString("full_name") }.firstOrNull()

    override fun currentGrant(companyId: UUID, agentUserId: UUID): AgentMandate? = jdbc.query(
        "select * from agent_clients where company_id = :companyId and agent_user_id = :agentId and status = 'ACTIVE'",
        mapOf("companyId" to companyId, "agentId" to agentUserId),
    ) { rs, _ -> mandate(rs) }.firstOrNull()

    override fun insert(mandate: AgentMandate) {
        jdbc.update(
            """
            insert into agent_clients (id, agent_user_id, company_id, assigned_at, expires_at, issued_source, issued_by)
            values (:id, :agentId, :companyId, :assignedAt, :expiresAt, :issuedSource, :issuedBy)
            """.trimIndent(),
            mapOf(
                "id" to mandate.id,
                "agentId" to mandate.agentUserId,
                "companyId" to mandate.companyId,
                "assignedAt" to Timestamp.from(mandate.assignedAt),
                "expiresAt" to mandate.expiresAt?.let(Timestamp::from),
                "issuedSource" to mandate.issuedSource.name,
                "issuedBy" to mandate.issuedBy,
            ),
        )
    }

    override fun markExpired(companyId: UUID, mandateId: UUID) {
        jdbc.update(
            "update agent_clients set status = 'EXPIRED' where company_id = :companyId and id = :mandateId and status = 'ACTIVE'",
            mapOf("companyId" to companyId, "mandateId" to mandateId),
        )
    }

    override fun revoke(mandate: AgentMandate) {
        jdbc.update(
            """
            update agent_clients
            set status = 'REVOKED', revoked_at = :revokedAt, revoked_source = :revokedSource, revoked_by = :revokedBy
            where company_id = :companyId and id = :id and status = 'ACTIVE'
            """.trimIndent(),
            mapOf(
                "id" to mandate.id,
                "companyId" to mandate.companyId,
                "revokedAt" to mandate.revokedAt?.let(Timestamp::from),
                "revokedSource" to mandate.revokedSource?.name,
                "revokedBy" to mandate.revokedBy,
            ),
        )
    }

    private fun mandate(rs: ResultSet) = AgentMandate(
        id = rs.getObject("id", UUID::class.java),
        agentUserId = rs.getObject("agent_user_id", UUID::class.java),
        companyId = rs.getObject("company_id", UUID::class.java),
        status = AgentMandateStatus.valueOf(rs.getString("status")),
        assignedAt = rs.getTimestamp("assigned_at").toInstant(),
        revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
        expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
        issuedSource = AgentMandateSource.valueOf(rs.getString("issued_source")),
        issuedBy = rs.getObject("issued_by", UUID::class.java),
        revokedSource = rs.getString("revoked_source")?.let(AgentMandateSource::valueOf),
        revokedBy = rs.getObject("revoked_by", UUID::class.java),
    )
}
