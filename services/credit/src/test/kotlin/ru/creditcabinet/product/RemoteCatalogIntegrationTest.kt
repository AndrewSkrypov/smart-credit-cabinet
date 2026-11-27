package ru.creditcabinet.product

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doCallRealMethod
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import org.springframework.transaction.support.TransactionSynchronizationManager
import ru.creditcabinet.agent.domain.AgentAccessDecision
import ru.creditcabinet.agent.domain.AgentAccessPort
import ru.creditcabinet.application.domain.CompanyDirectory
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.pipeline.FakeSources
import ru.creditcabinet.pipeline.MutableClock
import ru.creditcabinet.pipeline.PipelineFakesConfig
import ru.creditcabinet.pipeline.PipelineWorker
import ru.creditcabinet.product.infrastructure.CreditCatalogConfiguration
import ru.creditcabinet.product.infrastructure.RemoteProductCatalog
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        "spring.flyway.enabled=false",
        "cabinet.catalog.mode=remote",
        "cabinet.outbox.worker-enabled=false",
    ],
)
@AutoConfigureMockMvc
@Import(PipelineFakesConfig::class)
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

    @Autowired
    private lateinit var sources: FakeSources

    @Autowired
    private lateinit var clock: MutableClock

    @Autowired
    private lateinit var worker: PipelineWorker

    @MockitoSpyBean
    private lateinit var remote: RemoteProductCatalog

    private val riskId = UUID.randomUUID()
    private val agentId = UUID.randomUUID()
    private val direct = CompanySnapshot(UUID.randomUUID(), "000100000004", "Прямой клиент R1", LegalForm.ENTREPRENEUR)
    private val represented = CompanySnapshot(UUID.randomUUID(), "000100000005", "Клиент агента R1", LegalForm.ENTREPRENEUR)

    @Test
    fun `both HTTP submission paths pin the remote audit and reject stale consent without replaying writes`() {
        whenever(companies.find(direct.id)).thenReturn(direct)
        whenever(agentAccess.checkAccess(agentId, represented.id)).thenReturn(AgentAccessDecision(allowed = true, company = represented))
        val today = clock.instant().atZone(clock.zone).toLocalDate()
        sources.companies[direct.inn] = FakeSources.healthy(direct.inn, today, okved = "41.20")
        sources.companies[represented.inn] = FakeSources.healthy(represented.inn, today)

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
        var previewFailures = 0
        doAnswer {
            assertFalse(TransactionSynchronizationManager.isActualTransactionActive())
            previewFailures++
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Сбой чтения после записи")
        }.whenever(remote).latest(selected)
        val directId = post(applications, directClient, request).json(201)["id"].asText()
        assertEquals("DRAFT", put("$applications/$directId", directClient, request).json()["status"].asText())
        val agentId = post(agentApplications, agent, request + ("companyId" to represented.id)).json(201)["id"].asText()
        assertEquals("PENDING_CLIENT_CONFIRMATION", post("$agentApplications/$agentId/request-confirmation", agent).json()["status"].asText())
        assertEquals(4, previewFailures)
        doCallRealMethod().whenever(remote).latest(selected)

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
        val retryAgent = post(agentApplications, agent, request + mapOf("companyId" to represented.id, "productCode" to alternative))
            .json(201)["id"].asText()
        post("$agentApplications/$retryAgent/request-confirmation", agent).json()
        val local = CreditCatalogConfiguration().creditProductCatalog(jdbc, Clock.systemUTC(), "local")
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertFailsWith<ApiException> { local.snapshotForSubmission(selected) }.httpStatus)
        assertEquals(3L, local.version(selected, 3)?.version)

        val uncached = "R1_HTTP_${suffix}_C"
        post(bankProducts, admin, terms() + ("code" to uncached)).json(201)
        post("$bankProducts/$uncached/publish", admin, mapOf("expectedVersion" to 1, "reason" to "Публикация")).json()
        withLockedCatalog {
            assertEquals(503, post("$applications/$retry/submit", directClient, mapOf("acceptedProductVersion" to 2)).status)
            assertEquals(
                503,
                post("$applications/$retryAgent/confirm", representedClient, mapOf("acceptedProductVersion" to 2, "bureauConsent" to true)).status,
            )
            assertUnchangedDraft(retry, "DRAFT")
            assertUnchangedDraft(retryAgent, "PENDING_CLIENT_CONFIRMATION")
            assertEquals(0, jdbc.queryForObject("select count(*) from product_versions where code = ?", Int::class.java, uncached))
            val handed = get("$applications/$retryAgent", representedClient).json()
            assertEquals(2L, handed["product"]["version"].asLong())
        }
        val archived = terms() + mapOf("expectedVersion" to 2, "name" to "Архивный тариф", "baseRate" to 88, "lifecycleStatus" to "ARCHIVED")
        put("$bankProducts/$alternative", admin, archived).json()
        withoutCatalogAuthority {
            worker.runDue()
            val pinned = get("$applications/$directId", manager).json()
            assertEquals("MANUAL_REVIEW", pinned["status"].asText())
            assertEquals(3L, pinned["product"]["version"].asLong())
            post(
                "/api/v1/underwriting/applications/$directId/counter-offer",
                token(Roles.UNDERWRITER),
                mapOf("productCode" to alternative, "amount" to 500_000, "termMonths" to 12, "comment" to "Тариф из снимка"),
            ).json()
            val offer = get("$applications/$directId", directClient).json()["process"]["offer"]
            assertEquals(2L, offer["productVersion"].asLong())
            assertTrue(offer["rate"].asDouble() < 88)
            post("$applications/$directId/offer/accept", directClient).json()
            val contract = get("$applications/$directId/contract", directClient).json()
            assertTrue(contract["text"].asText().contains("Проверка удалённого Catalog"))
            assertFalse(contract["text"].asText().contains("Архивный тариф"))
        }
    }

    private fun assertUnchangedDraft(id: String, status: String) {
        val uuid = UUID.fromString(id)
        val application = jdbc.queryForMap(
            "select status, product_catalog_snapshot::text as snapshot from applications where id = ?",
            uuid,
        )
        assertEquals(status, application["status"])
        assertEquals("{}", application["snapshot"])
        assertEquals(0, jdbc.queryForObject("select count(*) from pipeline_jobs where application_id = ?", Int::class.java, uuid))
    }

    private fun withLockedCatalog(action: () -> Unit) = withCatalogOwner { owner ->
        owner.autoCommit = false
        try {
            owner.createStatement().use { statement ->
                statement.execute("lock table catalog_cutover.authority in access exclusive mode")
                action()
            }
        } finally {
            owner.rollback()
        }
    }

    private fun withoutCatalogAuthority(action: () -> Unit) = withCatalogOwner { owner ->
        owner.createStatement().use { statement ->
            assertEquals(
                1,
                statement.executeUpdate("update catalog_cutover.authority set writable = false where singleton and writable"),
            )
            try {
                action()
            } finally {
                assertEquals(1, statement.executeUpdate("update catalog_cutover.authority set writable = true where singleton and not writable"))
            }
        }
    }

    private fun withCatalogOwner(action: (Connection) -> Unit) {
        val url = checkNotNull(System.getenv("CATALOG_TEST_OWNER_JDBC_URL"))
        val user = checkNotNull(System.getenv("CATALOG_TEST_OWNER_DATABASE_USER"))
        val password = System.getenv("CATALOG_TEST_OWNER_DATABASE_PASSWORD").orEmpty()
        DriverManager.getConnection(url, user, password).use { owner ->
            require(owner.catalog == "catalog")
            action(owner)
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
