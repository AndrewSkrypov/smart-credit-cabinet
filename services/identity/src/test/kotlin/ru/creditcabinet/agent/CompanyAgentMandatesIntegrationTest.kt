package ru.creditcabinet.agent

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import ru.creditcabinet.auth.application.JwtService
import ru.creditcabinet.common.model.Inn
import ru.creditcabinet.user.domain.User
import ru.creditcabinet.user.domain.UserRole
import ru.creditcabinet.user.domain.UserStatus
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@SpringBootTest
@AutoConfigureMockMvc
class CompanyAgentMandatesIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var jwt: JwtService

    @Test
    fun `only a current company signer manages their own mandates for active agents`() {
        val signer = client()
        val outsider = client()
        val employee = client(signer.companyId!!, false)
        val agent = user(UserRole.AGENT)
        val blocked = user(UserRole.AGENT, status = UserStatus.BLOCKED)
        val expiry = Instant.now().plusSeconds(3600)
        val created = issue(signer, agent.id, expiry)
        assertEquals(201, created.status)
        val body = json(created)
        val mandateId = body["id"].asText()
        assertEquals(setOf("id", "agentUserId", "agentName", "status", "assignedAt", "expiresAt", "revokedAt"), body.fieldNames().asSequence().toSet())
        assertEquals(agent.id.toString(), body["agentUserId"].asText())
        assertEquals(agent.fullName, body["agentName"].asText())

        mvc.get(PATH).andExpect { status { isUnauthorized() } }
        mvc.get(PATH) { header("Authorization", "Bearer ${jwt.issue(agent)}") }.andExpect { status { isForbidden() } }
        mvc.get(PATH) { header("Authorization", "Bearer ${jwt.issue(employee.copy(canSign = true))}") }.andExpect { status { isForbidden() } }
        assertEquals(403, issue(employee, agent.id, expiry).status)
        assertEquals(403, revoke(employee, mandateId).status)
        assertEquals(404, revoke(outsider, mandateId).status)
        assertEquals(0, list(outsider).size())
        assertEquals(listOf(mandateId), list(signer).map { it["id"].asText() })
        assertEquals(404, issue(signer, blocked.id, expiry).status)
        assertEquals(404, issue(signer, employee.id, expiry).status)
        assertEquals(400, issue(signer, agent.id, Instant.now().minusSeconds(60)).status)
    }

    @Test
    fun `duplicate issuance and revocation preserve provenance while a new grant has a new ID`() {
        val signer = client()
        val agent = user(UserRole.AGENT)
        val expiry = Instant.now().plusSeconds(3600)
        val first = json(issue(signer, agent.id, expiry))["id"].asText()
        assertEquals(first, json(issue(signer, agent.id, expiry))["id"].asText())
        assertEquals(409, issue(signer, agent.id, expiry.plusSeconds(10)).status)
        assertEquals(first, access(agent, signer)["mandateId"].asText())
        assertEquals(204, revoke(signer, first).status)
        val revokedAt = list(signer).single()["revokedAt"].asText()
        assertEquals(204, revoke(signer, first).status)
        assertEquals(revokedAt, list(signer).single()["revokedAt"].asText())
        assertEquals(false, access(agent, signer)["allowed"].asBoolean())
        val issued = issue(signer, agent.id, expiry)
        assertEquals(201, issued.status)
        val second = json(issued)["id"].asText()
        assertNotEquals(first, second)
        assertEquals(second, access(agent, signer)["mandateId"].asText())
        assertEquals(mapOf(first to "REVOKED", second to "ACTIVE"), list(signer).associate { it["id"].asText() to it["status"].asText() })

        val lifecycle = jdbc.queryForMap("select issued_source, issued_by, revoked_source, revoked_by from agent_clients where id=?::uuid", first)
        assertEquals(mapOf("issued_source" to "COMPANY", "issued_by" to signer.id, "revoked_source" to "COMPANY", "revoked_by" to signer.id), lifecycle)
    }

    @Test
    fun `expired and blocked mandates are rejected by every agent lookup`() {
        val signer = client()
        val agent = user(UserRole.AGENT)
        val expiry = Instant.now().plusSeconds(3600)
        val first = json(issue(signer, agent.id, expiry))["id"].asText()
        jdbc.update(
            "update agent_clients set assigned_at=now()-interval '2 hours', expires_at=now()-interval '1 hour' where id=?::uuid",
            first,
        )
        assertEquals("EXPIRED", list(signer).single()["status"].asText())
        assertEquals(false, access(agent, signer)["allowed"].asBoolean())
        checkAgentClientLists(agent, 0)

        val next = json(issue(signer, agent.id, expiry))["id"].asText()
        assertNotEquals(first, next)
        assertEquals(next, access(agent, signer)["mandateId"].asText())
        assertEquals(mapOf(first to "EXPIRED", next to "ACTIVE"), list(signer).associate { it["id"].asText() to it["status"].asText() })
        checkAgentClientLists(agent, 1)

        jdbc.update("update users set status = 'BLOCKED' where id = ?", agent.id)
        assertEquals(false, access(agent, signer)["allowed"].asBoolean())
        assertEquals(0, internalCompanyIds(agent).size())
    }

    private fun checkAgentClientLists(agent: User, expected: Int) {
        assertEquals(expected, internalCompanyIds(agent).size())
        val clients = mvc.get("/api/v1/agent/clients") {
            header("Authorization", "Bearer ${jwt.issue(agent)}")
        }.andExpect { status { isOk() } }.andReturn().response
        assertEquals(expected, json(clients).size())
    }

    private fun internalCompanyIds(agent: User): JsonNode {
        val response = mvc.get("/internal/v1/agents/${agent.id}/companies")
            .andExpect { status { isOk() } }.andReturn().response
        return json(response)
    }

    private fun access(agent: User, signer: User): JsonNode {
        val response = mvc.get("/internal/v1/agents/${agent.id}/companies/${signer.companyId}/access")
            .andExpect { status { isOk() } }.andReturn().response
        return json(response)
    }

    private fun list(signer: User): JsonNode {
        val response = mvc.get(PATH) {
            header("Authorization", "Bearer ${jwt.issue(signer)}")
        }.andExpect { status { isOk() } }.andReturn().response
        return json(response)
    }

    private fun issue(signer: User, agentId: UUID, expiry: Instant): MockHttpServletResponse = mvc.post(PATH) {
        contentType = MediaType.APPLICATION_JSON
        content = mapper.writeValueAsString(mapOf("agentUserId" to agentId, "expiresAt" to expiry))
        header("Authorization", "Bearer ${jwt.issue(signer)}")
    }.andReturn().response

    private fun revoke(signer: User, id: String): MockHttpServletResponse = mvc.delete("$PATH/$id") {
        header("Authorization", "Bearer ${jwt.issue(signer)}")
    }.andReturn().response

    private fun json(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.getContentAsString(Charsets.UTF_8))

    private fun client(companyId: UUID = UUID.randomUUID(), canSign: Boolean = true): User {
        val prefix = companyId.toString().filter(Char::isDigit).take(9).padEnd(9, '0')
        val inn = (0..9).map { "$prefix$it" }.first(Inn::isValid)
        jdbc.update(
            "insert into companies (id,inn,name) values (?,?,?) on conflict (id) do nothing",
            companyId,
            inn,
            "Тестовая компания",
        )
        return user(UserRole.CLIENT, companyId, canSign)
    }

    private fun user(role: UserRole, companyId: UUID? = null, canSign: Boolean = false, status: UserStatus = UserStatus.ACTIVE): User {
        val id = UUID.randomUUID()
        val user = User(id, "Тестовый пользователь", "$id@example.test", role, status, companyId, canSign)
        jdbc.update(
            """
            insert into users (id, full_name, email, role, status, company_id, can_sign, password_hash)
            values (?, ?, ?, ?, ?, ?, ?, 'synthetic')
            """.trimIndent(),
            user.id,
            user.fullName,
            user.email,
            role.name,
            status.name,
            companyId,
            canSign,
        )
        return user
    }

    private companion object {
        const val PATH = "/api/v1/companies/me/agent-mandates"
    }
}
