package ru.creditcabinet.product

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import ru.creditcabinet.agent.domain.AgentAccessDecision
import ru.creditcabinet.agent.domain.AgentAccessPort
import ru.creditcabinet.application.domain.CompanyDirectory
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.assessment.domain.BankInfo
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.integration.CompanySources
import ru.creditcabinet.product.infrastructure.CreditCatalogConfiguration
import java.sql.DriverManager
import java.time.Clock
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@SpringBootTest(
    properties = [
        "spring.flyway.enabled=false",
        "cabinet.catalog.mode=remote",
        "cabinet.outbox.worker-enabled=false",
    ],
)
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "CATALOG_CREDIT_TEST", matches = "true")
class RemoteCatalogIntegrationTest {
    @Autowired
    private lateinit var mvc: MockMvc

    @Autowired
    private lateinit var verifier: JwtVerifier

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @MockitoBean
    private lateinit var companies: CompanyDirectory

    @MockitoBean
    private lateinit var agentAccess: AgentAccessPort

    @MockitoBean
    private lateinit var sources: CompanySources

    private val riskId = UUID.randomUUID()
    private val agentId = UUID.randomUUID()
    private val direct = CompanySnapshot(UUID.randomUUID(), "000100000004", "Прямой клиент R1", LegalForm.ENTREPRENEUR)
    private val represented = CompanySnapshot(UUID.randomUUID(), "000100000005", "Клиент агента R1", LegalForm.ENTREPRENEUR)

    @Test
    fun `both HTTP submission paths pin the remote audit and reject stale consent without replaying writes`() {
        whenever(companies.find(direct.id)).thenReturn(direct)
        whenever(agentAccess.checkAccess(agentId, represented.id)).thenReturn(AgentAccessDecision(allowed = true, company = represented))
        whenever(sources.bank(represented.inn)).thenReturn(BankInfo(true, null, emptyList()))

        val admin = token(Roles.RISK_ADMIN, riskId)
        val manager = token(Roles.MANAGER)
        val agent = token(Roles.AGENT, agentId)
        val directClient = token(Roles.CLIENT, company = direct.id)
        val representedClient = token(Roles.CLIENT, company = represented.id)
        val suffix = UUID.randomUUID().toString().take(8).uppercase()
        val selected = "R1_HTTP_${suffix}_A"
        val alternative = "R1_HTTP_${suffix}_B"
        val bankProducts = "/api/v1/bank/products"
        val applications = "/api/v1/applications"
        val agentApplications = "/api/v1/agent/applications"

        assertEquals(403, post(bankProducts, manager, terms() + ("code" to selected)).status)
        for (code in listOf(selected, alternative)) {
            val draft = post(bankProducts, admin, terms() + ("code" to code)).json(201)
            assertEquals(riskId.toString(), draft["authorId"].asText())
            assertEquals(
                2L,
                post("$bankProducts/$code/publish", admin, mapOf("expectedVersion" to 1, "reason" to "Публикация"))
                    .json()["product"]["version"].asLong(),
            )
        }

        val request = mapOf(
            "productCode" to selected,
            "purpose" to "WORKING_CAPITAL",
            "amount" to 2_000_000,
            "termMonths" to 12,
            "declaredRevenue12m" to 24_000_000,
            "bureauConsent" to true,
        )
        val directId = post(applications, directClient, request).json(201)["id"].asText()
        val agentId = post(agentApplications, agent, request + ("companyId" to represented.id)).json(201)["id"].asText()
        assertEquals("PENDING_CLIENT_CONFIRMATION", post("$agentApplications/$agentId/request-confirmation", agent).json()["status"].asText())

        val revised = put(
            "$bankProducts/$selected",
            admin,
            terms() + mapOf("expectedVersion" to 2, "agentCommissionRateBps" to 200, "baseRate" to 21),
        ).json()
        assertEquals(3L, revised["product"]["version"].asLong())
        assertEquals(409, post("$applications/$directId/submit", directClient, mapOf("acceptedProductVersion" to 2)).status)
        assertEquals(409, post("$applications/$agentId/confirm", representedClient, mapOf("acceptedProductVersion" to 2, "bureauConsent" to true)).status)
        assertEquals(0, jdbc.queryForObject("select count(*) from product_versions where code = ? and version = 3", Int::class.java, selected))

        assertEquals("SUBMITTED", post("$applications/$directId/submit", directClient, mapOf("acceptedProductVersion" to 3)).json()["status"].asText())
        assertEquals(
            "SUBMITTED",
            post("$applications/$agentId/confirm", representedClient, mapOf("acceptedProductVersion" to 3, "bureauConsent" to true)).json()["status"].asText(),
        )
        for (id in listOf(directId, agentId)) {
            val facts = jdbc.queryForMap(
                "select product_version, product_catalog_snapshot ->> ? as alternative from applications where id = ?",
                alternative,
                UUID.fromString(id),
            )
            assertEquals(3L, facts["product_version"])
            assertEquals("2", facts["alternative"])
        }
        assertEquals(
            40_000L,
            jdbc.queryForObject("select agent_commission_amount from applications where id = ?", Long::class.java, UUID.fromString(agentId)),
        )
        assertEquals(
            riskId,
            jdbc.queryForObject("select author_id from product_versions where code = ? and version = 3", UUID::class.java, selected),
        )
        post("$bankProducts/$selected/archive", admin, mapOf("expectedVersion" to 3, "reason" to "Архив")).json()
        val retry = post(applications, directClient, request + ("productCode" to alternative)).json(201)["id"].asText()
        val local = CreditCatalogConfiguration().creditProductCatalog(jdbc, Clock.systemUTC(), "local")
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertFailsWith<ApiException> { local.snapshotForSubmission(selected) }.httpStatus)
        assertEquals(3L, local.version(selected, 3)?.version)

        withoutCatalogAuthority {
            assertEquals(503, post("$applications/$retry/submit", directClient, mapOf("acceptedProductVersion" to 2)).status)
            assertEquals(
                "DRAFT",
                jdbc.queryForObject("select status from applications where id = ?", String::class.java, UUID.fromString(retry)),
            )
            assertEquals(
                0,
                jdbc.queryForObject("select count(*) from pipeline_jobs where application_id = ?", Int::class.java, UUID.fromString(retry)),
            )
            val pinned = get("$applications/$directId", manager).json()
            assertEquals(3L, pinned["product"]["version"].asLong())
        }
    }

    private fun withoutCatalogAuthority(action: () -> Unit) {
        val url = checkNotNull(System.getenv("CATALOG_TEST_OWNER_JDBC_URL"))
        val user = checkNotNull(System.getenv("CATALOG_TEST_OWNER_DATABASE_USER"))
        val password = System.getenv("CATALOG_TEST_OWNER_DATABASE_PASSWORD").orEmpty()
        DriverManager.getConnection(url, user, password).use { owner ->
            require(owner.catalog == "catalog")
            owner.createStatement().use { statement ->
                assertEquals(1, statement.executeUpdate("update catalog_cutover.authority set writable = false where singleton and writable"))
                try {
                    action()
                } finally {
                    assertEquals(1, statement.executeUpdate("update catalog_cutover.authority set writable = true where singleton and not writable"))
                }
            }
        }
    }

    private fun terms() = mapOf(
        "name" to "Проверка удалённого Catalog",
        "description" to "Синтетические условия R1",
        "purposes" to listOf("WORKING_CAPITAL"),
        "minAmount" to 300_000,
        "maxAmount" to 5_000_000,
        "minTermMonths" to 6,
        "maxTermMonths" to 24,
        "baseRate" to 20,
        "autoApprovalLimit" to 3_000_000,
        "revolving" to false,
        "reason" to "Синтетическая редакция",
    )

    private fun token(role: String, user: UUID = UUID.randomUUID(), company: UUID? = null): String =
        Jwts.builder().subject(user.toString()).claim(TokenClaims.ROLE, role)
            .claim(TokenClaims.FULL_NAME, "Проверка удалённого Catalog").claim(TokenClaims.CAN_SIGN, company != null)
            .apply { company?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }.expiration(Date(System.currentTimeMillis() + 120_000))
            .signWith(verifier.signingKey).compact()

    private fun post(path: String, token: String, body: Map<String, Any>? = null) = mvc.post(path) {
        header("Authorization", "Bearer $token")
        body?.let {
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(it)
        }
    }.andReturn().response

    private fun put(path: String, token: String, body: Map<String, Any>) = mvc.put(path) {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = mapper.writeValueAsString(body)
    }.andReturn().response

    private fun get(path: String, token: String) = mvc.get(path) { header("Authorization", "Bearer $token") }.andReturn().response

    private fun MockHttpServletResponse.json(expected: Int = 200): JsonNode {
        assertEquals(expected, status, contentAsString)
        return mapper.readTree(contentAsByteArray)
    }
}
