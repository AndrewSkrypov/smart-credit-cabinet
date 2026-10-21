package ru.creditcabinet.pipeline

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
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import ru.creditcabinet.application.domain.CompanyDirectory
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.assessment.domain.SourceKind
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.TokenClaims
import java.time.Duration
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Whole scenarios of the pipeline through the public API, with the external systems replaced by
 * fakes and a clock the test moves. The worker is off; the test runs due jobs itself.
 */
@SpringBootTest(properties = ["cabinet.pipeline.enabled=false"])
@AutoConfigureMockMvc
@Import(PipelineFakesConfig::class)
class PipelineScenarioTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var worker: PipelineWorker

    @Autowired private lateinit var clock: MutableClock

    @Autowired private lateinit var sources: FakeSources

    @Autowired private lateinit var core: FakeCore

    @MockitoBean private lateinit var companies: CompanyDirectory

    private val companyId = UUID.randomUUID()
    private val inn = "00" + (1..8).joinToString("") { Random.nextInt(10).toString() }
    private val today get() = clock.now.atZone(ZoneOffset.UTC).toLocalDate()
    private val client by lazy { token("CLIENT", companyId) }
    private val underwriter by lazy { token("UNDERWRITER") }
    private val manager by lazy { token("MANAGER") }

    @BeforeTest
    fun setUp() {
        // Jobs left by other tests must not run in this one.
        jdbc.update("update pipeline_jobs set status = 'CANCELLED' where status in ('PENDING', 'RUNNING')")
        whenever(companies.find(any())).thenAnswer { CompanySnapshot(it.getArgument(0), inn, "ИП Тест", LegalForm.ENTREPRENEUR) }
        sources.companies[inn] = FakeSources.healthy(inn, today)
    }

    @AfterTest
    fun tearDown() {
        sources.down.clear()
        core.down = false
    }

    private fun token(role: String, company: UUID? = null) =
        Jwts.builder().subject(UUID.randomUUID().toString()).claim(TokenClaims.ROLE, role).claim(TokenClaims.FULL_NAME, "Тест")
            .apply { company?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
            .claim(TokenClaims.CAN_SIGN, company != null)
            .expiration(Date(System.currentTimeMillis() + 3_600_000)).signWith(verifier.signingKey).compact()

    private fun json(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.getContentAsString(Charsets.UTF_8))

    private fun post(url: String, token: String, body: String? = null): MockHttpServletResponse =
        mockMvc.post(url) {
            header("Authorization", "Bearer $token")
            body?.let {
                contentType = MediaType.APPLICATION_JSON
                content = it
            }
        }.andReturn().response

    private fun get(id: String, token: String = client) = json(
        mockMvc.get("/api/v1/applications/$id") {
            header("Authorization", "Bearer $token")
        }.andReturn().response,
    )

    private fun status(id: String) = get(id)["status"].asText()

    private fun submitted(amount: Long = 1_000_000, product: String = "WORKING_CAPITAL", revenue: Long = 14_400_000): String {
        val created = post(
            "/api/v1/applications",
            client,
            """{"productCode":"$product","purpose":"WORKING_CAPITAL","amount":$amount,"termMonths":12,"declaredRevenue12m":$revenue,"declaredMonthlyDebtPayments":30000,"bureauConsent":true}""",
        )
        assertEquals(201, created.status, created.contentAsString)
        val id = json(created)["id"].asText()
        assertEquals(200, post("/api/v1/applications/$id/submit", client).status)
        return id
    }

    private fun events(id: String) =
        json(mockMvc.get("/api/v1/applications/$id/events") { header("Authorization", "Bearer $client") }.andReturn().response).map { it["event"].asText() }

    @Test
    fun `a healthy business goes from the request to the money without a person`() {
        val id = submitted()
        worker.runDue()
        val offered = get(id)
        assertEquals("OFFER_ISSUED", offered["status"].asText())
        assertEquals("APPROVED", offered["process"]["offer"]["kind"].asText())
        assertEquals(1_000_000, offered["process"]["offer"]["amount"].asLong())

        val accepted = json(post("/api/v1/applications/$id/offer/accept", client))
        assertEquals("SIGNING", accepted["status"].asText())
        assertEquals("424242", accepted["process"]["signing"]["code"].asText(), "the code is shown in the cabinet instead of an SMS")
        assertEquals(400, post("/api/v1/applications/$id/sign", client, """{"code":"000000"}""").status)
        assertEquals("DISBURSING", json(post("/api/v1/applications/$id/sign", client, """{"code":"424242"}"""))["status"].asText())

        worker.runDue()
        val disbursed = get(id)
        assertEquals("DISBURSED", disbursed["status"].asText())
        assertTrue(disbursed["process"]["loan"]["contractNumber"].asText().startsWith("КД-"))
        assertEquals(
            listOf("CREATED", "SUBMIT", "DATA_COMPLETE", "ENRICHMENT_DONE", "AUTO_OFFER", "ACCEPT_OFFER", "SIGN", "DISBURSED"),
            events(id),
        )
        val schedule = json(mockMvc.get("/api/v1/applications/$id/schedule") { header("Authorization", "Bearer $client") }.andReturn().response)
        assertEquals(12, schedule["schedule"].size())
    }

    @Test
    fun `missing documents are requested, and the application goes on once they are uploaded`() {
        sources.companies[inn] = FakeSources.healthy(inn, today, client = false)
        val id = submitted(amount = 4_000_000)
        worker.runDue()
        val requested = get(id)
        assertEquals("INFO_REQUESTED", requested["status"].asText())
        assertEquals(listOf("TAX_RETURN", "BANK_STATEMENT"), requested["process"]["infoRequest"]["items"].map { it["documentType"].asText() })

        val early = post("/api/v1/applications/$id/info-provided", client)
        assertEquals(400, early.status)
        assertTrue(json(early)["message"].asText().startsWith("Загрузите"))

        for (type in listOf("TAX_RETURN", "BANK_STATEMENT")) {
            mockMvc.multipart("/api/v1/applications/$id/documents") {
                file(MockMultipartFile("file", "$type.pdf", "application/pdf", "%PDF-1.7 synthetic".toByteArray()))
                param("type", type)
                header("Authorization", "Bearer $client")
            }.andExpect { status { isCreated() } }
        }
        assertEquals("SUBMITTED", json(post("/api/v1/applications/$id/info-provided", client))["status"].asText())
        worker.runDue()
        assertEquals("OFFER_ISSUED", status(id))
    }

    @Test
    fun `a silent bureau is retried, the application waits and goes to an underwriter after the timeout`() {
        sources.down += SourceKind.BUREAU
        val id = submitted()
        worker.runDue()
        assertEquals("ENRICHMENT", status(id))
        assertTrue("SOURCE_FAILED" in events(id))

        clock.advance(Duration.ofMinutes(61))
        worker.runDue()
        assertEquals("MANUAL_REVIEW", status(id))
        val card = json(mockMvc.get("/api/v1/underwriting/applications/$id") { header("Authorization", "Bearer $underwriter") }.andReturn().response)
        assertEquals("SOURCE_UNAVAILABLE", card["automaticDecision"]["manualReasons"][0]["code"].asText())
        assertEquals(listOf("APPROVE", "COUNTER_OFFER", "REQUEST_INFO", "REJECT"), card["actions"].map { it.asText() })

        assertEquals(400, post("/api/v1/underwriting/applications/$id/approve", underwriter).status, "no recommendation: a reason is required")
        assertEquals(403, post("/api/v1/underwriting/applications/$id/approve", manager, """{"comment":"ok"}""").status)
        assertEquals(200, post("/api/v1/underwriting/applications/$id/approve", underwriter, """{"comment":"Кредитная история проверена вручную"}""").status)
        assertEquals("OFFER_ISSUED", status(id))
    }

    @Test
    fun `a liquidated company is refused and the client sees why`() {
        sources.companies[inn] = FakeSources.healthy(inn, today, status = "LIQUIDATING")
        val id = submitted()
        worker.runDue()
        val rejected = get(id)
        assertEquals("REJECTED", rejected["status"].asText())
        assertEquals("SF_STATUS", rejected["process"]["outcome"]["reasonCode"].asText())
        assertTrue(rejected["process"]["outcome"]["explanation"][0].asText().startsWith("По данным ЕГРЮЛ"))
    }

    @Test
    fun `an underwriter can counter-offer a high-risk industry and ask for documents, a manager only ask`() {
        sources.companies[inn] = FakeSources.healthy(inn, today, okved = "41.20")
        val id = submitted()
        worker.runDue()
        assertEquals("MANUAL_REVIEW", status(id))

        assertEquals(403, post("/api/v1/underwriting/applications/$id/counter-offer", manager, """{"amount":500000,"termMonths":12,"comment":"x"}""").status)
        val asked =
            post(
                "/api/v1/underwriting/applications/$id/request-info",
                manager,
                """{"items":[{"question":"Какие объекты сейчас строите?"}],"comment":"Нужны пояснения"}""",
            )
        assertEquals(200, asked.status, asked.contentAsString)
        assertEquals("INFO_REQUESTED", status(id))
        assertEquals(400, post("/api/v1/applications/$id/info-provided", client).status, "a question needs an answer")
        post("/api/v1/applications/$id/info-provided", client, """{"answer":"Два жилых дома по госзаказу"}""")
        worker.runDue()
        assertEquals("MANUAL_REVIEW", status(id), "the industry rule still applies after the answer")

        val counter =
            post(
                "/api/v1/underwriting/applications/$id/counter-offer",
                underwriter,
                """{"amount":700000,"termMonths":24,"comment":"Снижаем риск: меньше сумма"}""",
            )
        assertEquals(200, counter.status, counter.contentAsString)
        val offer = get(id)["process"]["offer"]
        assertEquals("COUNTER", offer["kind"].asText())
        assertEquals(700_000, offer["amount"].asLong())
        assertEquals(listOf("Снижаем риск: меньше сумма"), offer["changes"].map { it.asText() })
    }

    @Test
    fun `an offer nobody answers expires, a failing core banking system is retried`() {
        val expiring = submitted()
        worker.runDue()
        clock.advance(Duration.ofDays(6))
        worker.runDue()
        val closed = get(expiring)
        assertEquals("CLOSED", closed["status"].asText())
        assertEquals("OFFER_EXPIRED", closed["closeReason"].asText())

        val paid = submitted(product = "WORKING_CAPITAL", amount = 900_000)
        worker.runDue()
        post("/api/v1/applications/$paid/offer/accept", client)
        core.down = true
        post("/api/v1/applications/$paid/sign", client, """{"code":"424242"}""")
        worker.runDue()
        assertEquals("DISBURSING", status(paid))
        assertTrue("DISBURSEMENT_FAILED" in events(paid))
        core.down = false
        clock.advance(Duration.ofMinutes(1))
        worker.runDue()
        assertEquals("DISBURSED", status(paid))
    }

    @Test
    fun `a withdrawn application is left alone by the pipeline`() {
        sources.down += SourceKind.TAX
        val id = submitted()
        worker.runDue()
        assertEquals("ENRICHMENT", status(id))
        assertEquals("WITHDRAWN", json(post("/api/v1/applications/$id/withdraw", client))["status"].asText())
        sources.down.clear()
        clock.advance(Duration.ofMinutes(10))
        worker.runDue()
        assertEquals("WITHDRAWN", status(id))
    }
}
