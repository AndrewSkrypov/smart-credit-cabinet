package ru.creditcabinet.pipeline

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
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
import ru.creditcabinet.application.domain.CompanyDirectory
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.TokenClaims
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(PipelineFakesConfig::class)
class RulesAndAnalyticsTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var worker: PipelineWorker

    @Autowired private lateinit var clock: MutableClock

    @Autowired private lateinit var sources: FakeSources

    @MockitoBean private lateinit var companies: CompanyDirectory

    private val companyId = UUID.randomUUID()
    private val inn = "00" + (1..8).joinToString("") { Random.nextInt(10).toString() }
    private val client by lazy { token("CLIENT", companyId) }
    private val manager by lazy { token("MANAGER") }
    private val risk by lazy { token("RISK_ADMIN") }

    private lateinit var initialParams: String

    @BeforeTest
    fun setUp() {
        initialParams = jdbc.queryForObject("select params::text from rule_sets order by version desc limit 1", String::class.java)!!
        jdbc.update("update pipeline_jobs set status = 'CANCELLED' where status in ('PENDING', 'RUNNING')")
        whenever(companies.find(any())).thenAnswer { CompanySnapshot(it.getArgument(0), inn, "ИП Тест", LegalForm.ENTREPRENEUR) }
        sources.companies[inn] = FakeSources.healthy(inn, clock.now.atZone(ZoneOffset.UTC).toLocalDate())
    }

    @AfterTest
    fun restorePolicy() {
        val current = jdbc.queryForObject("select params::text from rule_sets order by version desc limit 1", String::class.java)!!
        if (mapper.readTree(current) != mapper.readTree(initialParams)) {
            jdbc.update("insert into rule_sets (version,params,comment) select max(version)+1,?::jsonb,'Test policy restoration' from rule_sets", initialParams)
        }
    }

    private fun token(role: String, company: UUID? = null) =
        Jwts.builder().subject(UUID.randomUUID().toString()).claim(TokenClaims.ROLE, role)
            .apply { company?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
            .claim(TokenClaims.FULL_NAME, "Тестовый администратор").claim(TokenClaims.CAN_SIGN, company != null)
            .expiration(Date(System.currentTimeMillis() + 3_600_000)).signWith(verifier.signingKey).compact()

    private fun json(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.getContentAsString(Charsets.UTF_8))

    private fun getAs(url: String, token: String) = mockMvc.get(url) { header("Authorization", "Bearer $token") }.andReturn().response

    private fun postRules(token: String, body: String) =
        mockMvc.post("/api/v1/rules") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }.andReturn().response

    @Test
    fun `a stale editor cannot overwrite a rule version saved by another administrator`() {
        val original = json(getAs("/api/v1/rules", risk))
        val version = original["version"].asInt()
        val first = original["params"].deepCopy<ObjectNode>().put("maxLoad", 0.4)
        val stale = original["params"].deepCopy<ObjectNode>()
        (stale["ratingMargins"] as ObjectNode).put("B", 1.75)
        try {
            val saved = postRules(risk, mapper.writeValueAsString(mapOf("params" to first, "comment" to "Первый редактор", "expectedVersion" to version)))
            assertEquals(201, saved.status, saved.contentAsString)
            val rejected =
                postRules(risk, mapper.writeValueAsString(mapOf("params" to stale, "comment" to "Устаревший редактор", "expectedVersion" to version)))
            assertEquals(409, rejected.status, rejected.contentAsString)
            val current = json(getAs("/api/v1/rules", risk))
            assertEquals(version + 1, current["version"].asInt())
            assertEquals(0.4, current["params"]["maxLoad"].asDouble())
        } finally {
            jdbc.update(
                "insert into rule_sets (version,params,comment) select coalesce(max(version),0)+1,?::jsonb,'Test policy restoration' from rule_sets",
                mapper.writeValueAsString(original["params"]),
            )
        }
    }

    @Test
    fun `concurrent rule editors publish exactly one revision`() {
        val original = json(getAs("/api/v1/rules", risk))
        val executor = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val results = (1..2).map { n ->
                executor.submit<Int> {
                    start.await()
                    val params = original["params"].deepCopy<ObjectNode>().put("maxLoad", 0.4 + n * 0.01)
                    postRules(
                        risk,
                        mapper.writeValueAsString(
                            mapOf(
                                "params" to params,
                                "expectedVersion" to original["version"],
                                "comment" to "Конкурентный редактор $n",
                            ),
                        ),
                    ).status
                }
            }
            start.countDown()
            assertEquals(listOf(201, 409), results.map { it.get(20, TimeUnit.SECONDS) }.sorted())
            assertEquals(original["version"].asInt() + 1, json(getAs("/api/v1/rules", risk))["version"].asInt())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `unchanged rules and missing version are rejected without a new revision`() {
        val current = json(getAs("/api/v1/rules", risk))
        val missing = postRules(risk, mapper.writeValueAsString(mapOf("params" to current["params"], "comment" to "Нет версии")))
        assertEquals(400, missing.status, missing.contentAsString)
        val noop = postRules(
            risk,
            mapper.writeValueAsString(
                mapOf(
                    "params" to current["params"],
                    "expectedVersion" to current["version"],
                    "comment" to "Без изменений",
                ),
            ),
        )
        assertEquals(400, noop.status, noop.contentAsString)
        assertEquals(current["version"], json(getAs("/api/v1/rules", risk))["version"])
    }

    @Test
    fun `historical rule snapshots keep parameters author role and time and are bank only`() {
        val original = json(getAs("/api/v1/rules", risk))
        val changed = original["params"].deepCopy<ObjectNode>().put("maxLoad", 0.39)
        val saved =
            postRules(risk, mapper.writeValueAsString(mapOf("params" to changed, "expectedVersion" to original["version"], "comment" to "Снимок автора")))
        assertEquals(201, saved.status, saved.contentAsString)
        val version = json(saved)["version"].asInt()
        val snapshot = json(getAs("/api/v1/rules/$version", manager))
        assertEquals(changed, snapshot["params"])
        assertEquals("Тестовый администратор", snapshot["metadata"]["authorName"].asText())
        assertEquals("RISK_ADMIN", snapshot["metadata"]["authorRole"].asText())
        assertTrue(snapshot["metadata"]["createdAt"].asText().isNotBlank())
        assertEquals(original["params"], json(getAs("/api/v1/rules/${original["version"].asInt()}", risk))["params"])
        assertEquals(403, getAs("/api/v1/rules/$version", client).status)
        assertEquals(404, getAs("/api/v1/rules/2147483647", risk).status)
    }

    @Test
    fun `the bank reads the rules, only the risk administrator changes them, clients see neither rules nor analytics`() {
        assertEquals(403, getAs("/api/v1/rules", client).status)
        assertEquals(403, getAs("/api/v1/analytics/funnel", client).status)
        val current = json(getAs("/api/v1/rules", manager))
        val body = mapper.writeValueAsString(mapOf("params" to current["params"], "expectedVersion" to current["version"], "comment" to "Проверка"))
        assertEquals(403, postRules(manager, body).status)
    }

    @Test
    fun `inconsistent thresholds are refused with the reason`() {
        val original = json(getAs("/api/v1/rules", risk))
        val params = original["params"] as ObjectNode
        (params["ratingCutoffs"] as ObjectNode).put("B", 85)
        params.putArray("highRiskOkved").add("62").add("4")
        val response = postRules(risk, mapper.writeValueAsString(mapOf("params" to params, "expectedVersion" to original["version"], "comment" to "Ошибка")))
        assertEquals(400, response.status)
        val message = json(response)["message"].asText()
        assertTrue(message.contains("A > B > C > D"), message)
        assertTrue(message.contains("Классы ОКВЭД"), message)
        assertTrue(message.contains("одновременно"), message)
    }

    @Test
    fun `a new version applies to the next assessment and the funnel counts the application`() {
        val current = json(getAs("/api/v1/rules", risk))
        val changed = current["params"].deepCopy<ObjectNode>().put("maxLoad", 0.36)
        val saved = postRules(
            risk,
            mapper.writeValueAsString(
                mapOf(
                    "params" to changed,
                    "expectedVersion" to current["version"],
                    "comment" to "Пересмотр порогов",
                ),
            ),
        )
        assertEquals(201, saved.status, saved.contentAsString)
        val version = json(saved)["version"].asInt()
        assertEquals(current["version"].asInt() + 1, version)
        assertEquals(version, json(saved)["versions"][0]["version"].asInt())

        val created = json(
            mockMvc.post("/api/v1/applications") {
                header("Authorization", "Bearer $client")
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"productCode":"WORKING_CAPITAL","purpose":"WORKING_CAPITAL","amount":1000000,"termMonths":12,"declaredRevenue12m":14400000,"bureauConsent":true}"""
            }.andReturn().response,
        )
        val id = created["id"].asText()
        mockMvc.post("/api/v1/applications/$id/submit") { header("Authorization", "Bearer $client") }
        worker.runDue()
        assertEquals(version, jdbc.queryForObject("select rule_set_version from decisions where application_id = ?::uuid", Int::class.java, id))

        val analytics = json(getAs("/api/v1/analytics/funnel?from=2026-01-01T00:00:00Z&to=2027-01-01T00:00:00Z", manager))
        val funnel = analytics["funnel"].associate { it["code"].asText() to it["applications"].asInt() }
        assertTrue(funnel.getValue("SUBMITTED") >= 1 && funnel.getValue("OFFER_ISSUED") >= 1, funnel.toString())
        assertTrue(funnel.getValue("SUBMITTED") >= funnel.getValue("DECIDED"))
        assertTrue(analytics["automaticOutcomes"].any { it["outcome"].asText() == "APPROVE" })
        assertTrue(analytics["stageTimes"].any { it["status"].asText() == "SUBMITTED" })
        assertTrue(analytics["currentStatuses"]["OFFER_ISSUED"].asInt() >= 1)
    }
}
