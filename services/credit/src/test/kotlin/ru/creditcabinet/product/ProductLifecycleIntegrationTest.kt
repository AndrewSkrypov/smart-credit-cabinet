package ru.creditcabinet.product

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import ru.creditcabinet.application.domain.CompanyDirectory
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.pipeline.FakeSources
import ru.creditcabinet.pipeline.MutableClock
import ru.creditcabinet.pipeline.PipelineFakesConfig
import ru.creditcabinet.pipeline.PipelineWorker
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductLifecycleStatus
import ru.creditcabinet.product.domain.problems
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(PipelineFakesConfig::class)
class ProductLifecycleIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var clock: MutableClock

    @Autowired private lateinit var sources: FakeSources

    @Autowired private lateinit var worker: PipelineWorker

    @MockitoBean private lateinit var companies: CompanyDirectory

    private val inn = "00" + UUID.randomUUID().leastSignificantBits.toString().filter(Char::isDigit).take(8)
    private val client by lazy { token("CLIENT", UUID.randomUUID()) }
    private val risk by lazy { token("RISK_ADMIN") }
    private val underwriter by lazy { token("UNDERWRITER") }

    private fun token(role: String, company: UUID? = null) = Jwts.builder().subject(UUID.randomUUID().toString())
        .claim(TokenClaims.FULL_NAME, "Риск-администратор Тест")
        .claim(TokenClaims.ROLE, role)
        .claim(TokenClaims.COMPANY_ID, company?.toString())
        .claim(TokenClaims.CAN_SIGN, company != null)
        .expiration(Date(System.currentTimeMillis() + 3_600_000))
        .signWith(verifier.signingKey)
        .compact()

    @BeforeTest
    fun setup() {
        jdbc.update("update pipeline_jobs set status = 'CANCELLED' where status in ('PENDING', 'RUNNING')")
        whenever(companies.find(any())).thenAnswer { CompanySnapshot(it.getArgument(0), inn, "ООО Жизненный цикл", LegalForm.ENTREPRENEUR) }
        sources.companies[inn] = FakeSources.healthy(inn, clock.instant().atZone(ZoneOffset.UTC).toLocalDate())
    }

    private fun json(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.getContentAsString(Charsets.UTF_8))

    private fun get(path: String, token: String = client) =
        mvc.get(path) { header("Authorization", "Bearer $token") }.andReturn().response

    private fun post(path: String, body: Any? = null, token: String = risk) = mvc.post(path) {
        header("Authorization", "Bearer $token")
        if (body != null) {
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(body)
        }
    }.andReturn().response

    private fun put(path: String, body: Any, token: String = risk) = mvc.put(path) {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = mapper.writeValueAsString(body)
    }.andReturn().response

    private fun uniqueCode(): String = "LIFE_${UUID.randomUUID().toString().replace("-", "").take(10).uppercase()}"

    private fun createBody(
        code: String,
        effectiveFrom: Instant? = null,
        effectiveTo: Instant? = null,
        agentCommissionRateBps: Int = 250,
        reason: String = "Запуск нового продукта",
    ): Map<String, Any?> = mapOf(
        "code" to code,
        "name" to "Сезонный кредит $code",
        "description" to "Финансирование сезонных закупок малого бизнеса",
        "purposes" to listOf("WORKING_CAPITAL"),
        "minAmount" to 300_000,
        "maxAmount" to 10_000_000,
        "minTermMonths" to 3,
        "maxTermMonths" to 24,
        "baseRate" to BigDecimal("19.50"),
        "autoApprovalLimit" to 3_000_000,
        "revolving" to false,
        "effectiveFrom" to effectiveFrom,
        "effectiveTo" to effectiveTo,
        "agentCommissionRateBps" to agentCommissionRateBps,
        "reason" to reason,
    )

    @Test
    fun `Product problems validates effective window and agent commission bps and active is derived`() {
        val t1 = Instant.parse("2026-06-01T00:00:00Z")
        val t2 = Instant.parse("2026-12-01T00:00:00Z")
        val valid = Product(
            code = "UNIT_VALID",
            name = "Валидный продукт",
            description = "Описание",
            purposes = setOf(FinancingPurpose.WORKING_CAPITAL),
            minAmount = 100_000,
            maxAmount = 5_000_000,
            minTermMonths = 3,
            maxTermMonths = 24,
            baseRate = BigDecimal("18.50"),
            autoApprovalLimit = 2_000_000,
            revolving = false,
            lifecycleStatus = ProductLifecycleStatus.DRAFT,
            effectiveFrom = t1,
            effectiveTo = t2,
            agentCommissionRateBps = 150,
        )
        assertTrue(valid.problems().isEmpty())
        assertFalse(valid.active)
        assertTrue(valid.copy(lifecycleStatus = ProductLifecycleStatus.ACTIVE).active)
        assertFalse(valid.copy(lifecycleStatus = ProductLifecycleStatus.ARCHIVED).active)
        assertTrue(valid.copy(effectiveFrom = t1, effectiveTo = t1).problems().isEmpty())
        assertTrue(valid.copy(agentCommissionRateBps = 0).problems().isEmpty())
        assertTrue(valid.copy(agentCommissionRateBps = 1000).problems().isEmpty())
        assertTrue(valid.copy(effectiveFrom = t2, effectiveTo = t1).problems().isNotEmpty())
        assertTrue(valid.copy(agentCommissionRateBps = -1).problems().isNotEmpty())
        assertTrue(valid.copy(agentCommissionRateBps = 1001).problems().isNotEmpty())
    }

    @Test
    fun `full lifecycle draft publish archive restore preserves submitted snapshot and append-only trigger`() {
        val code = uniqueCode()
        val effectiveFrom = clock.instant().minusSeconds(3600)
        val effectiveTo = clock.instant().plusSeconds(86_400 * 30)

        // 1. Create in DRAFT (v1): hidden from public catalog, visible to bank staff
        val created = post("/api/v1/bank/products", createBody(code, effectiveFrom, effectiveTo, agentCommissionRateBps = 250))
        assertEquals(201, created.status, created.contentAsString)
        val v1 = json(created)
        assertEquals(1L, v1["product"]["version"].asLong())
        assertEquals("DRAFT", v1["product"]["lifecycleStatus"].asText())
        assertEquals(false, v1["product"]["active"].asBoolean())
        assertEquals(250, v1["product"]["agentCommissionRateBps"].asInt())

        val publicAfterDraft = json(get("/api/v1/products")).map { it["code"].asText() }
        assertFalse(code in publicAfterDraft, "DRAFT product must not appear in GET /api/v1/products")
        assertEquals(404, get("/api/v1/products/$code").status)
        val bankList = json(get("/api/v1/bank/products", underwriter)).map { it["code"].asText() }
        assertTrue(code in bankList, "Bank staff must see DRAFT product in /api/v1/bank/products")

        // 2. Publish -> ACTIVE (v2): appears in public catalog
        val published = post(
            "/api/v1/bank/products/$code/publish",
            mapOf("expectedVersion" to 1, "reason" to "Публикация продукта в каталог"),
        )
        assertEquals(200, published.status, published.contentAsString)
        val v2 = json(published)
        assertEquals(2L, v2["product"]["version"].asLong())
        assertEquals("ACTIVE", v2["product"]["lifecycleStatus"].asText())
        assertEquals(true, v2["product"]["active"].asBoolean())

        val publicAfterPublish = json(get("/api/v1/products")).map { it["code"].asText() }
        assertTrue(code in publicAfterPublish, "ACTIVE product must appear in GET /api/v1/products")
        assertEquals(200, get("/api/v1/products/$code").status)

        // Submit an application on v2 before archiving
        val appCreate = post(
            "/api/v1/applications",
            mapOf(
                "productCode" to code,
                "purpose" to "WORKING_CAPITAL",
                "amount" to 1_000_000,
                "termMonths" to 12,
                "declaredRevenue12m" to 14_400_000,
                "declaredMonthlyDebtPayments" to 30_000,
                "bureauConsent" to true,
            ),
            token = client,
        )
        assertEquals(201, appCreate.status, appCreate.contentAsString)
        val appId = json(appCreate)["id"].asText()
        assertEquals(200, post("/api/v1/applications/$appId/submit", token = client).status)
        worker.runDue()
        val beforeArchive = json(get("/api/v1/applications/$appId", client))
        assertEquals("OFFER_ISSUED", beforeArchive["status"].asText())

        // 3. Archive -> ARCHIVED (v3): disappears from public catalog, submitted application keeps reading v2 snapshot
        val archived = post(
            "/api/v1/bank/products/$code/archive",
            mapOf("expectedVersion" to 2, "reason" to "Временный вывод из продаж"),
        )
        assertEquals(200, archived.status, archived.contentAsString)
        val v3 = json(archived)
        assertEquals(3L, v3["product"]["version"].asLong())
        assertEquals("ARCHIVED", v3["product"]["lifecycleStatus"].asText())
        assertEquals(false, v3["product"]["active"].asBoolean())

        val publicAfterArchive = json(get("/api/v1/products")).map { it["code"].asText() }
        assertFalse(code in publicAfterArchive, "ARCHIVED product must disappear from GET /api/v1/products")
        assertEquals(404, get("/api/v1/products/$code").status)

        val appAfterArchive = json(get("/api/v1/applications/$appId", client))
        assertEquals("OFFER_ISSUED", appAfterArchive["status"].asText())
        assertEquals(code, appAfterArchive["product"]["code"].asText())
        assertEquals(2L, appAfterArchive["product"]["version"].asLong())
        assertEquals(2L, appAfterArchive["process"]["offer"]["productVersion"].asLong())
        assertEquals(beforeArchive["process"]["offer"], appAfterArchive["process"]["offer"])

        // 4. Restore -> ACTIVE (v4): returns to public catalog
        val restored = post(
            "/api/v1/bank/products/$code/restore",
            mapOf("expectedVersion" to 3, "reason" to "Возврат продукта в продажи"),
        )
        assertEquals(200, restored.status, restored.contentAsString)
        val v4 = json(restored)
        assertEquals(4L, v4["product"]["version"].asLong())
        assertEquals("ACTIVE", v4["product"]["lifecycleStatus"].asText())
        assertEquals(true, v4["product"]["active"].asBoolean())

        val publicAfterRestore = json(get("/api/v1/products")).map { it["code"].asText() }
        assertTrue(code in publicAfterRestore, "Restored product must reappear in GET /api/v1/products")

        val history = json(get("/api/v1/bank/products/$code", risk))
        assertEquals(listOf(4L, 3L, 2L, 1L), history.map { it["product"]["version"].asLong() })
        assertEquals(listOf("ACTIVE", "ARCHIVED", "ACTIVE", "DRAFT"), history.map { it["product"]["lifecycleStatus"].asText() })

        // 5. Append-only trigger on product_versions remains active
        assertFails { jdbc.update("update product_versions set lifecycle_status = 'DRAFT' where code = ? and version = 2", code) }
        assertFails { jdbc.update("delete from product_versions where code = ? and version = 1", code) }
    }

    @Test
    fun `invalid effective dates, commission bps, transitions, conflicts and roles are rejected`() {
        val code = uniqueCode()
        val start = Instant.parse("2026-09-01T00:00:00Z")
        val end = Instant.parse("2026-08-01T00:00:00Z")

        assertEquals(400, post("/api/v1/bank/products", createBody(code, effectiveFrom = start, effectiveTo = end)).status)
        assertEquals(400, post("/api/v1/bank/products", createBody(code, agentCommissionRateBps = -1)).status)
        assertEquals(400, post("/api/v1/bank/products", createBody(code, agentCommissionRateBps = 1001)).status)
        assertEquals(403, post("/api/v1/bank/products", createBody(code), token = underwriter).status)
        assertEquals(403, post("/api/v1/bank/products", createBody(code), token = client).status)

        assertEquals(201, post("/api/v1/bank/products", createBody(code)).status)
        assertEquals(409, post("/api/v1/bank/products", createBody(code)).status)

        // Cannot restore a DRAFT product
        assertEquals(
            400,
            post("/api/v1/bank/products/$code/restore", mapOf("expectedVersion" to 1, "reason" to "Недопустимый переход")).status,
        )
        // Stale expectedVersion returns 409
        assertEquals(
            409,
            post("/api/v1/bank/products/$code/publish", mapOf("expectedVersion" to 99, "reason" to "Устаревшая версия")).status,
        )
        // Non-risk role cannot publish
        assertEquals(
            403,
            post("/api/v1/bank/products/$code/publish", mapOf("expectedVersion" to 1, "reason" to "Нет прав"), token = underwriter).status,
        )
        // Edit with invalid effective window or commission bps returns 400
        assertEquals(
            400,
            put(
                "/api/v1/bank/products/$code",
                createBody(code, effectiveFrom = start, effectiveTo = end) + mapOf("expectedVersion" to 1, "active" to false),
            ).status,
        )
        assertEquals(
            400,
            put(
                "/api/v1/bank/products/$code",
                createBody(code, agentCommissionRateBps = 1500) + mapOf("expectedVersion" to 1, "active" to false),
            ).status,
        )
    }
}
