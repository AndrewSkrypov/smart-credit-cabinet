package ru.creditcabinet.agent

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.Roles
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
class AgentClientIntegrationTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jwtVerifier: JwtVerifier

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    private fun signIn(email: String, password: String = "Demo2026") =
        mockMvc.post("/api/v1/auth/sign-in") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$email","password":"$password"}"""
        }.andReturn().response

    private fun tokenOf(body: String): String = mapper.readTree(body)["token"].asText()

    @Test
    fun `agent signs in with AGENT role, no companyId and canSign false`() {
        val response = signIn("agent@demo.test")
        assertEquals(200, response.status, response.contentAsString)

        val body = response.getContentAsString(Charsets.UTF_8)
        val claims = jwtVerifier.parseClaims(tokenOf(body))
        assertEquals(Roles.AGENT, claims.role)
        assertTrue(claims.isAgent)
        assertFalse(claims.isClient)
        assertFalse(claims.isBankStaff)
        assertNull(claims.companyId)
        assertFalse(claims.canSign)

        val userNode = mapper.readTree(body)["user"]
        assertEquals("AGENT", userNode["role"].asText())
        assertTrue(userNode["companyId"].isNull)
        assertFalse(userNode["canSign"].asBoolean())
    }

    @Test
    fun `agent sees exactly 4 assigned clients and agent2 sees 1 client`() {
        val agentToken = tokenOf(signIn("agent@demo.test").getContentAsString(Charsets.UTF_8))
        val agentResult = mockMvc.get("/api/v1/agent/clients") {
            header("Authorization", "Bearer $agentToken")
        }.andExpect {
            status { isOk() }
        }.andReturn().response.getContentAsString(Charsets.UTF_8)

        val clients = mapper.readTree(agentResult)
        assertEquals(4, clients.size())
        val companyIds = clients.map { UUID.fromString(it["companyId"].asText()) }.toSet()
        assertEquals(
            setOf(
                KOTOVA_COMPANY_ID,
                SEVERNAYA_MEBEL_COMPANY_ID,
                VEKTOR_LOGISTIK_COMPANY_ID,
                TECHNOPARK_COMPANY_ID,
            ),
            companyIds,
        )
        clients.forEach { node ->
            assertTrue(node["inn"].asText().isNotBlank())
            assertTrue(node["legalForm"].asText() in setOf("LEGAL_ENTITY", "ENTREPRENEUR"))
            assertTrue(node["assignedAt"].asText().isNotBlank())
        }

        val agent2Token = tokenOf(signIn("agent2@demo.test").getContentAsString(Charsets.UTF_8))
        val agent2Result = mockMvc.get("/api/v1/agent/clients") {
            header("Authorization", "Bearer $agent2Token")
        }.andExpect {
            status { isOk() }
        }.andReturn().response.getContentAsString(Charsets.UTF_8)

        val agent2Clients = mapper.readTree(agent2Result)
        assertEquals(1, agent2Clients.size())
        assertEquals(AGRO_COMPANY_ID, UUID.fromString(agent2Clients[0]["companyId"].asText()))
    }

    @Test
    fun `non-agent callers get 403 and anonymous callers get 401 on agent clients endpoint`() {
        val clientToken = tokenOf(signIn("client@demo.test").getContentAsString(Charsets.UTF_8))
        mockMvc.get("/api/v1/agent/clients") {
            header("Authorization", "Bearer $clientToken")
        }.andExpect { status { isForbidden() } }

        val managerToken = tokenOf(signIn("manager@demo.test").getContentAsString(Charsets.UTF_8))
        mockMvc.get("/api/v1/agent/clients") {
            header("Authorization", "Bearer $managerToken")
        }.andExpect { status { isForbidden() } }

        mockMvc.get("/api/v1/agent/clients").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `internal endpoints list assigned companies and enforce active mandate on access check`() {
        val agentClaims = jwtVerifier.parseClaims(tokenOf(signIn("agent@demo.test").getContentAsString(Charsets.UTF_8)))
        val agentId = agentClaims.userId

        val listBody = mockMvc.get("/internal/v1/agents/$agentId/companies")
            .andExpect { status { isOk() } }
            .andReturn().response.getContentAsString(Charsets.UTF_8)
        val listedIds = mapper.readTree(listBody).map { UUID.fromString(it.asText()) }.toSet()
        assertEquals(
            setOf(
                KOTOVA_COMPANY_ID,
                SEVERNAYA_MEBEL_COMPANY_ID,
                VEKTOR_LOGISTIK_COMPANY_ID,
                TECHNOPARK_COMPANY_ID,
            ),
            listedIds,
        )

        mockMvc.get("/internal/v1/agents/$agentId/companies/$KOTOVA_COMPANY_ID/access")
            .andExpect {
                status { isOk() }
                jsonPath("$.allowed") { value(true) }
                jsonPath("$.company.id") { value(KOTOVA_COMPANY_ID.toString()) }
                jsonPath("$.company.inn") { value("000100000004") }
                jsonPath("$.company.legalForm") { value("ENTREPRENEUR") }
            }

        mockMvc.get("/internal/v1/agents/$agentId/companies/$STROYGRAD_COMPANY_ID/access")
            .andExpect {
                status { isOk() }
                jsonPath("$.allowed") { value(false) }
                jsonPath("$.company") { doesNotExist() }
            }

        try {
            jdbc.update(
                "update agent_clients set status = 'REVOKED', revoked_at = now(), revoked_source = 'LEGACY' where agent_user_id = ? and company_id = ?",
                agentId,
                KOTOVA_COMPANY_ID,
            )

            mockMvc.get("/internal/v1/agents/$agentId/companies/$KOTOVA_COMPANY_ID/access")
                .andExpect {
                    status { isOk() }
                    jsonPath("$.allowed") { value(false) }
                    jsonPath("$.company") { doesNotExist() }
                }
        } finally {
            jdbc.update(
                "update agent_clients set status = 'ACTIVE', revoked_at = null, revoked_source = null where agent_user_id = ? and company_id = ?",
                agentId,
                KOTOVA_COMPANY_ID,
            )
        }
    }

    companion object {
        private val KOTOVA_COMPANY_ID = UUID.fromString("6f1d4c1e-0b8e-4c55-9a51-000000000001")
        private val SEVERNAYA_MEBEL_COMPANY_ID = UUID.fromString("6f1d4c1e-0b8e-4c55-9a51-000000000002")
        private val VEKTOR_LOGISTIK_COMPANY_ID = UUID.fromString("6f1d4c1e-0b8e-4c55-9a51-000000000003")
        private val TECHNOPARK_COMPANY_ID = UUID.fromString("6f1d4c1e-0b8e-4c55-9a51-000000000007")
        private val AGRO_COMPANY_ID = UUID.fromString("6f1d4c1e-0b8e-4c55-9a51-000000000008")
        private val STROYGRAD_COMPANY_ID = UUID.fromString("6f1d4c1e-0b8e-4c55-9a51-000000000010")
    }
}
