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
import kotlin.random.Random
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

    @BeforeTest
    fun setUp() {
        jdbc.update("update pipeline_jobs set status = 'CANCELLED' where status in ('PENDING', 'RUNNING')")
        whenever(companies.find(any())).thenAnswer { CompanySnapshot(it.getArgument(0), inn, "ИП Тест", LegalForm.ENTREPRENEUR) }
        sources.companies[inn] = FakeSources.healthy(inn, clock.now.atZone(ZoneOffset.UTC).toLocalDate())
    }

    private fun token(role: String, company: UUID? = null) =
        Jwts.builder().subject(UUID.randomUUID().toString()).claim(TokenClaims.ROLE, role)
            .apply { company?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
            .claim(TokenClaims.CAN_SIGN, company != null)
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
    fun `the bank reads the rules, only the risk administrator changes them, clients see neither rules nor analytics`() {
        assertEquals(403, getAs("/api/v1/rules", client).status)
        assertEquals(403, getAs("/api/v1/analytics/funnel", client).status)
        val current = json(getAs("/api/v1/rules", manager))
        val body = mapper.writeValueAsString(mapOf("params" to current["params"], "comment" to "Проверка"))
        assertEquals(403, postRules(manager, body).status)
    }

    @Test
    fun `inconsistent thresholds are refused with the reason`() {
        val params = json(getAs("/api/v1/rules", risk))["params"] as ObjectNode
        (params["ratingCutoffs"] as ObjectNode).put("B", 85)
        params.putArray("highRiskOkved").add("62").add("4")
        val response = postRules(risk, mapper.writeValueAsString(mapOf("params" to params, "comment" to "Ошибка")))
        assertEquals(400, response.status)
        val message = json(response)["message"].asText()
        assertTrue(message.contains("A > B > C > D"), message)
        assertTrue(message.contains("Классы ОКВЭД"), message)
        assertTrue(message.contains("одновременно"), message)
    }

    @Test
    fun `a new version applies to the next assessment and the funnel counts the application`() {
        val current = json(getAs("/api/v1/rules", risk))
        val saved = postRules(risk, mapper.writeValueAsString(mapOf("params" to current["params"], "comment" to "Пересмотр порогов без изменений")))
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
