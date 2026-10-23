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
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.application.domain.CompanyDirectory
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.pipeline.FakeCore
import ru.creditcabinet.pipeline.FakeSigning
import ru.creditcabinet.pipeline.FakeSources
import ru.creditcabinet.pipeline.MutableClock
import ru.creditcabinet.pipeline.PipelineFakesConfig
import ru.creditcabinet.pipeline.PipelineWorker
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductCatalog
import ru.creditcabinet.product.domain.ProductVersions
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(PipelineFakesConfig::class)
class ProductVersionsIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tx: TransactionTemplate

    @Autowired private lateinit var catalog: ProductCatalog

    @Autowired private lateinit var versions: ProductVersions

    @Autowired private lateinit var clock: MutableClock

    @Autowired private lateinit var sources: FakeSources

    @Autowired private lateinit var worker: PipelineWorker

    @Autowired private lateinit var signing: FakeSigning

    @Autowired private lateinit var core: FakeCore

    @MockitoBean private lateinit var companies: CompanyDirectory
    private val originals = linkedMapOf<String, Product>()
    private val inn = "00" + UUID.randomUUID().leastSignificantBits.toString().filter(Char::isDigit).take(8)
    private val client by lazy { token("CLIENT", UUID.randomUUID()) }
    private val risk by lazy { token("RISK_ADMIN") }
    private val underwriter by lazy { token("UNDERWRITER") }
    private fun token(role: String, company: UUID? = null) = Jwts.builder().subject(UUID.randomUUID().toString())
        .claim(TokenClaims.ROLE, role).claim(TokenClaims.COMPANY_ID, company?.toString()).claim(TokenClaims.CAN_SIGN, company != null)
        .expiration(Date(System.currentTimeMillis() + 3_600_000)).signWith(verifier.signingKey).compact()

    @BeforeTest fun setup() {
        jdbc.update("update pipeline_jobs set status = 'CANCELLED' where status in ('PENDING', 'RUNNING')")
        whenever(companies.find(any())).thenAnswer { CompanySnapshot(it.getArgument(0), inn, "ИП Тест", LegalForm.ENTREPRENEUR) }
        sources.companies[inn] = FakeSources.healthy(inn, clock.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate())
    }

    @AfterTest fun restore() {
        for ((code, original) in originals) {
            val current = catalog.latest(code)!!
            tx.executeWithoutResult {
                versions.replace(original, current.version, UUID.randomUUID(), "Восстановление исходных условий после проверки", clock.instant())
            }
        }
    }

    private fun json(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.getContentAsString(Charsets.UTF_8))
    private fun get(path: String, token: String = client) = mvc.get(path) { header("Authorization", "Bearer $token") }.andReturn().response
    private fun post(path: String, body: Any? = null, token: String = client) = mvc.post(path) {
        header("Authorization", "Bearer $token")
        if (body != null) {
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(body)
        }
    }.andReturn().response
    private fun edit(product: Product, token: String = risk, reason: String = "Проверка новой версии") = mvc.put("/api/v1/bank/products/${product.code}") {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = mapper.writeValueAsString(mapper.convertValue(product, Map::class.java) + mapOf("expectedVersion" to product.version, "reason" to reason))
    }.andReturn().response
    private fun remember(code: String = "WORKING_CAPITAL"): Product = catalog.latest(code)!!.also { originals.putIfAbsent(code, it) }
    private fun draft(token: String = client): String {
        val response = post(
            "/api/v1/applications",
            mapOf(
                "productCode" to "WORKING_CAPITAL",
                "purpose" to "WORKING_CAPITAL",
                "amount" to 1_000_000,
                "termMonths" to 12,
                "declaredRevenue12m" to 14_400_000,
                "declaredMonthlyDebtPayments" to 30000,
                "bureauConsent" to true,
            ),
            token,
        )
        assertEquals(201, response.status, response.contentAsString)
        return json(response)["id"].asText()
    }
    private fun submitted(token: String = client): String = draft(token).also {
        val sent = post("/api/v1/applications/$it/submit", token = token)
        assertEquals(200, sent.status, sent.contentAsString)
    }
    private fun finish(id: String, token: String = client) {
        assertEquals(200, post("/api/v1/applications/$id/offer/accept", token = token).status)
        assertEquals(200, post("/api/v1/applications/$id/sign", mapOf("code" to "424242"), token).status)
        worker.runDue()
        assertEquals("DISBURSED", json(get("/api/v1/applications/$id", token))["status"].asText())
    }

    @Test fun `an edit cannot change a submitted pipeline, existing offer, contract or revolving disbursement`() {
        val original = remember()
        val offered = submitted()
        worker.runDue()
        val offeredBefore = json(get("/api/v1/applications/$offered"))["process"]["offer"]
        val secondClient = token("CLIENT", UUID.randomUUID())
        val waiting = submitted(secondClient)
        val changed = edit(
            original.copy(
                name = "Изменённый продукт",
                baseRate = 29.toBigDecimal(),
                revolving = true,
                maxAmount = 900_000,
                autoApprovalLimit = 300_000,
                maxTermMonths = 12,
                active = false,
            ),
        )
        assertEquals(200, changed.status, changed.contentAsString)
        assertEquals(404, get("/api/v1/products/${original.code}").status)
        assertEquals(404, get("/api/v1/products/${original.code}/calculation?amount=1000000&termMonths=12").status)
        worker.runDue()
        val after = json(get("/api/v1/applications/$waiting", secondClient))
        assertEquals("OFFER_ISSUED", after["status"].asText(), "changed limits must not send the old application to manual review")
        assertEquals(original.name, after["product"]["name"].asText())
        assertEquals(original.version, after["product"]["version"].asLong())
        assertEquals(original.baseRate.toDouble(), after["process"]["offer"]["rate"].asDouble())
        assertEquals(offeredBefore, json(get("/api/v1/applications/$offered"))["process"]["offer"])
        finish(offered)
        finish(waiting, secondClient)
        assertTrue(signing.texts.getValue("contract-$waiting").contains("Продукт: ${original.name}"))
        assertTrue(!signing.texts.getValue("contract-$waiting").contains("Изменённый продукт"))
        assertEquals(false, core.requests.getValue(UUID.fromString(waiting)).revolving)
        assertEquals(
            listOf(false, false),
            jdbc.queryForList(
                "select revolving from loans where application_id in (?, ?)",
                Boolean::class.java,
                UUID.fromString(offered),
                UUID.fromString(waiting),
            ),
        )
        assertTrue(json(get("/api/v1/applications/$waiting/schedule", secondClient))["schedule"].size() == 12)
        val current = catalog.latest(original.code)!!
        assertEquals(200, edit(original.copy(version = current.version, baseRate = 23.toBigDecimal())).status)
        val fresh = submitted()
        worker.runDue()
        assertEquals(23.0, json(get("/api/v1/applications/$fresh"))["process"]["offer"]["rate"].asDouble())
    }

    @Test fun `draft autosave does not acknowledge changed terms, stale acknowledgement fails and current acknowledgement pins`() {
        val original = remember()
        val id = draft()
        assertEquals(200, edit(original.copy(baseRate = 24.toBigDecimal())).status)
        val loaded = json(get("/api/v1/applications/$id"))
        assertEquals(original.version, loaded["product"]["version"].asLong())
        assertEquals(original.version + 1, loaded["currentProduct"]["version"].asLong())
        val update = mvc.put("/api/v1/applications/$id") {
            header("Authorization", "Bearer $client")
            contentType = MediaType.APPLICATION_JSON
            content =
                """{"productCode":"WORKING_CAPITAL","purpose":"WORKING_CAPITAL","amount":1000000,"termMonths":12,"declaredRevenue12m":14400000,"bureauConsent":true}"""
        }.andReturn().response
        assertEquals(200, update.status)
        assertEquals(409, post("/api/v1/applications/$id/submit").status)
        assertEquals(409, post("/api/v1/applications/$id/submit", mapOf("acceptedProductVersion" to original.version)).status)
        assertEquals(200, post("/api/v1/applications/$id/submit", mapOf("acceptedProductVersion" to original.version + 1)).status)
        assertEquals(original.version + 1, json(get("/api/v1/applications/$id"))["product"]["version"].asLong())
        assertFails { jdbc.update("update applications set product_version = ? where id = ?", original.version, UUID.fromString(id)) }
        assertFails { jdbc.update("update product_versions set reason = 'changed' where code = ?", original.code) }
        assertFails { jdbc.update("delete from product_versions where code = ?", original.code) }
    }

    @Test fun `bank roles can read history but only risk can edit and invalid edits leave no version`() {
        val original = remember()
        for (role in listOf("MANAGER", "UNDERWRITER", "RISK_ADMIN")) {
            val token = token(role)
            assertEquals(200, get("/api/v1/bank/products", token).status)
            assertEquals(200, get("/api/v1/bank/products/${original.code}", token).status)
        }
        assertEquals(403, edit(original, underwriter).status)
        assertEquals(403, edit(original, client).status)
        assertEquals(403, get("/api/v1/bank/products", client).status)
        val invalid = listOf(
            original.copy(minAmount = 0),
            original.copy(minAmount = original.maxAmount + 1),
            original.copy(baseRate = 101.toBigDecimal()),
            original.copy(baseRate = 0.toBigDecimal()),
            original.copy(autoApprovalLimit = 0),
            original.copy(purposes = emptySet()),
            original.copy(minTermMonths = 0),
            original.copy(maxTermMonths = 121),
        )
        for (candidate in invalid) assertEquals(400, edit(candidate).status)
        assertEquals(400, edit(original, reason = " ").status)
        assertEquals(original.version, catalog.latest(original.code)!!.version)
        val saved = edit(original.copy(baseRate = 26.toBigDecimal()))
        assertEquals(200, saved.status)
        assertEquals(409, edit(original).status)
        assertEquals(26.0, json(get("/api/v1/products/${original.code}"))["baseRate"].asDouble())
        assertEquals(26.0, json(get("/api/v1/products/${original.code}/calculation?amount=1000000&termMonths=12"))["rate"].asDouble())
        val matches = json(post("/api/v1/products/match", mapOf("purpose" to "WORKING_CAPITAL", "amount" to 1_000_000, "termMonths" to 12)))
        assertEquals(26.0, matches.first { it["product"]["code"].asText() == original.code }["product"]["baseRate"].asDouble())
    }

    @Test fun `concurrent editors have exactly one winner`() {
        val original = remember()
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val edits = (1..2).map {
                executor.submit<Int> {
                    start.await()
                    edit(original.copy(baseRate = (21 + it).toBigDecimal())).status
                }
            }
            start.countDown()
            assertEquals(listOf(200, 409), edits.map { it.get(20, TimeUnit.SECONDS) }.sorted())
            assertEquals(original.version + 1, catalog.latest(original.code)!!.version)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun `an alternative counter product uses its submission snapshot even after it is disabled`() {
        val alternative = remember("BUSINESS_CARD")
        sources.companies[inn] = FakeSources.healthy(inn, clock.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate(), okved = "41.20")
        val id = submitted()
        worker.runDue()
        assertEquals("MANUAL_REVIEW", json(get("/api/v1/applications/$id"))["status"].asText())
        assertEquals(200, edit(alternative.copy(baseRate = 88.toBigDecimal(), revolving = false, active = false)).status)
        val counter = post(
            "/api/v1/underwriting/applications/$id/counter-offer",
            mapOf(
                "productCode" to "BUSINESS_CARD",
                "amount" to 500_000,
                "termMonths" to 12,
                "comment" to "Проверено вручную, предлагаем прежний лимит",
            ),
            underwriter,
        )
        assertEquals(200, counter.status, counter.contentAsString)
        val offer = json(get("/api/v1/applications/$id"))["process"]["offer"]
        assertEquals(alternative.version, offer["productVersion"].asLong())
        assertTrue(offer["rate"].asDouble() < 88)
        finish(id)
        assertEquals(true, jdbc.queryForObject("select revolving from loans where application_id = ?", Boolean::class.java, UUID.fromString(id)))
    }
}
