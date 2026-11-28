package ru.creditcabinet.financing.scenario

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.agent.domain.AgentAccessDecision
import ru.creditcabinet.agent.domain.AgentAccessPort
import ru.creditcabinet.application.domain.Actor
import ru.creditcabinet.application.domain.ActorType
import ru.creditcabinet.application.domain.ApplicationJournal
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.JournalNote
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.assessment.domain.BankInfo
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.document.domain.ApplicationDocument
import ru.creditcabinet.document.domain.DocumentRepository
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.integration.domain.CompanySources
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductVersions
import java.math.BigDecimal
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Clock
import java.time.Duration
import java.util.Date
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "server.address=127.0.0.1",
        "server.forward-headers-strategy=none",
        "springdoc.api-docs.enabled=true",
        "cabinet.identity.url=",
        "cabinet.catalog.mode=local",
        "cabinet.outbox.worker-enabled=false",
    ],
)
@ActiveProfiles("agent-mcp-local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AgentMcpHttpIntegrationTest {
    @LocalServerPort private var port: Int = 0

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var applications: ApplicationRepository

    @Autowired private lateinit var documents: DocumentRepository

    @Autowired private lateinit var journal: ApplicationJournal

    @Autowired private lateinit var products: ProductVersions

    @Autowired private lateinit var transactions: TransactionTemplate

    @Autowired private lateinit var dataSource: DataSource

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var clock: Clock

    @MockitoBean private lateinit var mandates: AgentAccessPort

    @MockitoBean private lateinit var sources: CompanySources

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).proxy(ProxySelector.of(null)).build()
    private val origin get() = "http://127.0.0.1:$port"

    @Test
    fun `synthetic agent uses three live Credit routes without mutating saved state`() {
        dataSource.connection.use {
            val url = URI.create(it.metaData.url.removePrefix("jdbc:"))
            assertEquals("postgresql", url.scheme)
            assertTrue(url.host in setOf("127.0.0.1", "localhost"), "Use only the disposable loopback PostgreSQL fixture")
        }
        val agentId = UUID.randomUUID()
        val application = fixture(agentId)
        val own = token(Roles.AGENT, agentId)
        val base = "/api/v1/agent"
        val prioritiesPath = "$base/mcp/priorities"
        val summaryPath = "$base/mcp/applications/${application.id}/summary"
        val webPath = "$base/applications/${application.id}/financing-scenario"
        val previewPath = "$base/mcp/applications/${application.id}/financing-scenario/preview"
        val paths = json(request("GET", "/v3/api-docs/credit"))["paths"]
        assertTrue(paths.path("$base/mcp/priorities").has("get"))
        assertTrue(paths.path("$base/mcp/applications/{id}/summary").has("get"))
        assertTrue(paths.path("$base/mcp/applications/{id}/financing-scenario/preview").has("post"))
        assertTrue(paths.path("$base/applications/{id}/financing-scenario/preview").has("post"))

        assertEquals(401, request("GET", summaryPath).statusCode())
        assertCode(403, "FORBIDDEN", request("GET", summaryPath, token(Roles.UNDERWRITER)))
        assertCode(409, "PREVIEW_NOT_READY", request("POST", previewPath, own, previewInput(5)))

        val saved = json(request("PUT", webPath, own, WEB_SAVE))
        assertEquals(1, saved["revision"].asInt())
        val before = creditMcpSnapshot(jdbc, application.id)
        val priorities = json(request("GET", "$prioritiesPath?limit=1", own))
        val summary = json(request("GET", summaryPath, own))
        assertEquals("credit", priorities["source"].asText())
        assertEquals("credit", summary["source"].asText())
        assertEquals(application.id.toString(), summary["application"]["applicationId"].asText())
        assertEquals(summary["application"], priorities["items"].single())
        assertEquals(
            setOf("applicationId", "number", "companyName", "status", "actionOwner", "actionLabel", "actionDeadline", "cabinetPath"),
            summary["application"].fieldNames().asSequence().toSet(),
        )
        assertEquals("1", summary["savedScenarioRevision"].asText())

        val expected = mapOf(5 to listOf("104730.99", "188208.39"), 6 to listOf("-35269.01", "48208.39"))
        val results = listOf(5, 6, 5).map { delay ->
            val trial = json(request("POST", previewPath, own, previewInput(delay)))
            assertEquals("credit", trial["source"].asText())
            assertTrue(trial["preview"].asBoolean())
            assertEquals(summary["applicationVersion"], trial["basis"]["applicationVersion"])
            assertEquals("1", trial["basis"]["savedScenarioRevision"].asText())
            assertEquals(delay == 5, trial["inputsUsed"]["receiptIncludedByPayment"].asBoolean())
            assertEquals(5, trial["inputsUsed"]["lastIncludedDelayDays"].asInt())
            assertEquals(expected.getValue(delay), trial["options"].map { it["balanceOnPaymentDateRub"].asText() })
            assertEquals(listOf("185269.01", "101791.61"), trial["options"].map { it["monthlyPaymentRub"].asText() })
            assertEquals(listOf("223228.15", "442998.50"), trial["options"].map { it["totalInterestRub"].asText() })
            val actual = saved["options"].map { it["balanceOnPaymentDate"].decimalValue().toPlainString() }
            assertEquals(expected.getValue(5), actual)
            assertEquals(before, creditMcpSnapshot(jdbc, application.id))
            trial["options"]
        }
        assertEquals(results.first(), results.last())

        val unknown = request("GET", "$base/mcp/applications/${UUID.randomUUID()}/summary", own)
        assertCode(404, "CASE_NOT_FOUND", unknown)
        for (route in listOf(summaryPath, previewPath)) {
            val reply = if (route == previewPath) {
                request("POST", route, token(Roles.AGENT), previewInput(5))
            } else {
                request("GET", route, token(Roles.AGENT))
            }
            assertCode(404, "CASE_NOT_FOUND", reply)
            assertEquals(unknown.body(), reply.body())
        }
        assertCode(400, "INVALID_INPUT", request("POST", previewPath, own, previewInput(5).replace("5,", "5.5,")))
        assertCode(400, "INVALID_INPUT", request("GET", "$prioritiesPath?limit=21", own))

        runNodeIfConfigured(application.id, agentId)
        assertEquals(before, creditMcpSnapshot(jdbc, application.id))
        whenever(mandates.checkAccess(agentId, application.company.id)).thenReturn(AgentAccessDecision(false))
        whenever(mandates.listCompanyIds(agentId)).thenReturn(emptyList())
        val denied = request("GET", summaryPath, own)
        assertCode(404, "CASE_NOT_FOUND", denied)
        assertEquals(unknown.body(), denied.body())
        assertTrue(json(request("GET", prioritiesPath, own))["items"].isEmpty)
        assertEquals(before, creditMcpSnapshot(jdbc, application.id))
    }

    private fun fixture(agentId: UUID): LoanApplication {
        val product = Product(
            code = "MCP_" + UUID.randomUUID().toString().replace("-", "").take(12),
            name = "Synthetic MCP comparison",
            description = "Synthetic HTTP verification",
            purposes = setOf(FinancingPurpose.WORKING_CAPITAL),
            minAmount = 100_000,
            maxAmount = 3_000_000,
            minTermMonths = 6,
            maxTermMonths = 24,
            baseRate = BigDecimal("20"),
            autoApprovalLimit = 1_000_000,
            revolving = false,
        )
        val revision = assertNotNull(transactions.execute { products.create(product, agentId, "Synthetic HTTP fixture", clock.instant()) })
        val company = CompanySnapshot(UUID.randomUUID(), "000100000004", "Синтетическая компания", LegalForm.ENTREPRENEUR)
        whenever(mandates.listCompanyIds(agentId)).thenReturn(listOf(company.id))
        whenever(mandates.checkAccess(agentId, company.id)).thenReturn(AgentAccessDecision(true, company))
        whenever(sources.bank(company.inn)).thenReturn(BankInfo(client = true, settlementAccount = null, monthlyInflows = emptyList()))
        val saved = applications.save(
            LoanApplication(
                company = company,
                createdBy = agentId,
                terms = ApplicationTerms(product.code, FinancingPurpose.WORKING_CAPITAL, amount = 2_000_000, termMonths = 12),
                createdAt = clock.instant(),
                productVersion = revision.product.version,
                agentId = agentId,
            ),
        )
        journal.append(saved.note(JournalNote.CREATED, Actor(ActorType.AGENT, agentId), clock.instant()))
        documents.save(
            ApplicationDocument(
                applicationId = saved.id,
                type = DocumentType.BANK_STATEMENT,
                fileName = "synthetic-private.pdf",
                storedName = "synthetic-${UUID.randomUUID()}.pdf",
                sizeBytes = 1,
                uploadedBy = agentId,
                uploadedAt = clock.instant(),
                privateToBank = true,
            ),
        )
        return saved
    }

    private fun token(role: String, userId: UUID = UUID.randomUUID()): String =
        Jwts.builder().subject(userId.toString()).claim(TokenClaims.ROLE, role)
            .expiration(Date(clock.millis() + Duration.ofMinutes(2).toMillis()))
            .signWith(verifier.signingKey).compact()

    private fun request(method: String, path: String, jwt: String? = null, body: String? = null): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(10))
        jwt?.let { builder.header("Authorization", "Bearer $it") }
        if (body != null) builder.header("Content-Type", "application/json")
        val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
        return http.send(builder.method(method, publisher).build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun json(response: HttpResponse<String>): JsonNode {
        assertEquals(200, response.statusCode())
        return mapper.readTree(response.body())
    }

    private fun assertCode(status: Int, code: String, response: HttpResponse<String>) {
        assertEquals(status, response.statusCode())
        assertEquals(code, mapper.readTree(response.body())["code"].asText())
    }

    private fun previewInput(delay: Int) =
        """{"mode":"SAVED_SCENARIO","delayDays":$delay,"alternativeTermMonths":24}"""

    private fun runNodeIfConfigured(applicationId: UUID, agentId: UUID) {
        val script = System.getenv("CABINET_MCP_TEST_SCRIPT")?.let(Path::of) ?: return
        val sha = System.getenv("CABINET_CREDIT_SOURCE_SHA")
        require(sha != null && sha.matches(Regex("[a-f0-9]{40}"))) { "Provide the exact verified Credit SHA with the Node script" }
        require(script.isAbsolute && Files.isRegularFile(script)) { "Provide an absolute Node script path" }
        val permissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
        val directory = Files.createTempDirectory("cabinet-credit-http-", permissions)
        val tokenFile = directory.resolve("token")
        try {
            Files.createFile(tokenFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            Files.writeString(tokenFile, token(Roles.AGENT, agentId))
            val builder = ProcessBuilder("node", script.toString()).directory(script.parent.toFile())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD)
            val processEnv = builder.environment()
            val path = processEnv["PATH"]
            processEnv.clear()
            path?.let { processEnv["PATH"] = it }
            processEnv["CABINET_CREDIT_ORIGIN"] = origin
            processEnv["CABINET_CREDIT_TOKEN_FILE"] = tokenFile.toString()
            processEnv["CABINET_CREDIT_APPLICATION_ID"] = applicationId.toString()
            processEnv["CABINET_CREDIT_SOURCE_SHA"] = sha
            val process = builder.start()
            try {
                assertTrue(process.waitFor(45, TimeUnit.SECONDS), "Synthetic MCP Node consumer timed out")
                assertEquals(0, process.exitValue(), "Synthetic MCP Node consumer failed")
            } finally {
                if (process.isAlive) {
                    process.destroyForcibly()
                    process.waitFor(5, TimeUnit.SECONDS)
                }
            }
        } finally {
            Files.deleteIfExists(tokenFile)
            Files.deleteIfExists(directory)
        }
    }

    companion object {
        private const val WEB_SAVE = """
            {"openingBalance":150000,"expectedReceiptAmount":140000,"expectedReceiptDate":"2026-12-10",
             "paymentDate":"2026-12-15","delayDays":5,"alternativeTermMonths":24,"selectedTermMonths":24}
        """

        @JvmStatic
        @DynamicPropertySource
        fun testSigningKey(registry: DynamicPropertyRegistry) {
            val key = UUID.randomUUID().toString() + UUID.randomUUID()
            registry.add("cabinet.auth.jwt-secret") { key }
        }
    }
}
