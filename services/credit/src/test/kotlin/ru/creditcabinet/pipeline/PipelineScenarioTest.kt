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
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.generated.GeneratedArtifacts
import ru.creditcabinet.offer.ContractTexts
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Whole scenarios of the pipeline through the public API, with the external systems replaced by
 * fakes and a clock the test moves. The worker is off; the test runs due jobs itself.
 */
@SpringBootTest
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

    @Autowired private lateinit var signing: FakeSigning

    @Autowired private lateinit var artifacts: GeneratedArtifacts

    @Autowired private lateinit var fileStore: FileStore

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
        jdbc.update("update generated_artifacts set attempts=5 where status <> 'READY'")
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
    fun `source receipt timestamps are exposed only in the bank card`() {
        val id = submitted()
        worker.runDue()
        val card = json(mockMvc.get("/api/v1/underwriting/applications/$id") { header("Authorization", "Bearer $underwriter") }.andReturn().response)
        assertEquals(SourceKind.entries.map { it.name }.toSet(), card["sourceFetchedAt"].fieldNames().asSequence().toSet())
        card["sourceFetchedAt"].forEach { assertEquals(clock.now, Instant.parse(it.asText())) }
        assertTrue(!get(id).has("sourceFetchedAt"), "Source metadata must not leak into the client DTO")
        mockMvc.get("/api/v1/underwriting/applications/$id") { header("Authorization", "Bearer $client") }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `manual approval keeps the policy snapshot of its automatic assessment`() = verifyManualPolicySnapshot("approve")

    @Test
    fun `manual counter offer keeps the policy snapshot of its automatic assessment`() = verifyManualPolicySnapshot("counter-offer")

    private fun verifyManualPolicySnapshot(action: String) {
        sources.companies[inn] = FakeSources.healthy(inn, today, okved = "41.20")
        val id = submitted()
        worker.runDue()
        assertEquals("MANUAL_REVIEW", status(id))
        val card = json(mockMvc.get("/api/v1/underwriting/applications/$id") { header("Authorization", "Bearer $underwriter") }.andReturn().response)
        val originalVersion = card["automaticDecision"]["ruleSetVersion"].asInt()
        val originalRules = json(mockMvc.get("/api/v1/rules") { header("Authorization", "Bearer $underwriter") }.andReturn().response)
        val rating = card["automaticDecision"]["rating"].asText()
        val productRate = jdbc.queryForObject(
            "select v.base_rate from applications a join product_versions v on v.code=a.product_code and v.version=a.product_version where a.id=?::uuid",
            Double::class.java,
            id,
        )!!
        val expectedRate = productRate + originalRules["params"]["ratingMargins"][rating].asDouble()
        try {
            jdbc.update(
                "insert into rule_sets (version,params,created_at,comment) select version+1,jsonb_set(params,'{ratingMargins,$rating}',to_jsonb(?::numeric)),now(),'New policy after assessment' from rule_sets order by version desc limit 1",
                originalRules["params"]["ratingMargins"][rating].asDouble() + 2,
            )
            val result =
                post("/api/v1/underwriting/applications/$id/$action", underwriter, """{"amount":900000,"termMonths":12,"comment":"Проверено вручную"}""")
            assertEquals(200, result.status, result.contentAsString)
            assertEquals(expectedRate, get(id)["process"]["offer"]["rate"].asDouble())
            assertEquals(
                originalVersion,
                jdbc.queryForObject(
                    "select rule_set_version from decisions where application_id=?::uuid and decided_by_type='UNDERWRITER'",
                    Int::class.java,
                    id,
                ),
            )
        } finally {
            jdbc.update(
                "insert into rule_sets (version,params,comment) select coalesce(max(version),0)+1,?::jsonb,'Test policy restoration' from rule_sets",
                mapper.writeValueAsString(originalRules["params"]),
            )
        }
    }

    @Test
    fun `the signing text is saved once matches the gateway and survives replacement codes and disbursement`() {
        val id = submitted()
        worker.runDue()
        fun text(token: String = client) = mockMvc.get("/api/v1/applications/$id/contract") { header("Authorization", "Bearer $token") }.andReturn().response
        val preview = json(text())
        assertTrue(preview["snapshot"].isNull)
        assertEquals(false, preview["legacySummary"].asBoolean())
        assertEquals(200, post("/api/v1/applications/$id/offer/accept", client).status)
        val original = json(text())
        val snapshot = original["snapshot"]
        assertEquals(true, original["signable"].asBoolean())
        assertEquals(signing.texts.getValue("contract-$id"), original["text"].asText())
        assertEquals(ContractTexts.sha256(original["text"].asText()), snapshot["sha256"].asText())
        repeat(2) {
            assertEquals(200, post("/api/v1/applications/$id/signing/code", client).status)
            assertEquals(snapshot, json(text())["snapshot"])
        }
        assertEquals(1, jdbc.queryForObject("select count(*) from contract_text_snapshots where application_id=?::uuid", Int::class.java, id))
        assertFails { jdbc.update("update contract_text_snapshots set contract_text='changed' where application_id=?::uuid", id) }
        assertEquals(404, text(token("CLIENT", UUID.randomUUID())).status)
        assertEquals(snapshot, json(text(manager))["snapshot"])
        assertEquals(200, post("/api/v1/applications/$id/sign", client, """{"code":"424242"}""").status)
        worker.runDue()
        val issued = json(text())
        assertEquals(snapshot, issued["snapshot"])
        assertTrue(!issued["signedAt"].isNull)
        assertEquals(false, issued["signable"].asBoolean())
    }

    @Test
    fun `generated files keep preview and final sets stable and authorize every download and retry`() {
        val id = submitted()
        worker.runDue()
        fun list(token: String = client) = mockMvc.get("/api/v1/applications/$id/artifacts") { header("Authorization", "Bearer $token") }.andReturn().response
        val preview = json(list())
        assertEquals(2, preview.size())
        assertTrue(preview.all { it["phase"].asText() == "PREVIEW" && it["status"].asText() == "PENDING" && it["downloadUrl"].isNull })
        assertEquals(preview, json(list()))
        val pendingId = preview[0]["id"].asText()
        assertEquals(
            409,
            mockMvc.get("/api/v1/applications/$id/artifacts/$pendingId/download") {
                header("Authorization", "Bearer $client")
            }.andReturn().response.status,
        )
        repeat(2) { assertTrue(artifacts.generateOne()) }
        assertTrue(!artifacts.generateOne())
        val readyPreview = json(list())
        val previewIds = readyPreview.map { it["id"].asText() }
        assertEquals(200, post("/api/v1/applications/$id/offer/accept", client).status)
        assertEquals(200, post("/api/v1/applications/$id/signing/code", client).status)
        assertEquals(readyPreview, json(list()), "acceptance and a new code do not create extra preliminary copies")
        assertEquals(200, post("/api/v1/applications/$id/sign", client, """{"code":"424242"}""").status)
        worker.runDue()
        val loan = get(id)["process"]["loan"]
        val final = json(list())
        assertEquals(3, final.size())
        assertTrue(final.all { it["phase"].asText() == "FINAL" && it["id"].asText() !in previewIds })
        repeat(2) { assertTrue(artifacts.generateOne()) }
        artifacts.notifyReadyGroups()
        assertEquals(0, jdbc.queryForObject("select count(*) from generated_artifact_notices where application_id=?::uuid", Int::class.java, id))
        assertTrue(artifacts.generateOne())
        assertTrue(!artifacts.generateOne())
        val ready = json(list())
        assertTrue(ready.all { it["status"].asText() == "READY" }, ready.toString())
        assertEquals(5, jdbc.queryForObject("select count(*) from generated_artifacts where application_id=?::uuid", Int::class.java, id))
        assertEquals(0, get(id)["documents"].size(), "generated files must not become uploaded client documents")
        repeat(2) { artifacts.notifyReadyGroups() }
        assertEquals(1, jdbc.queryForObject("select count(*) from generated_artifact_notices where application_id=?::uuid", Int::class.java, id))
        assertEquals(
            1,
            jdbc.queryForObject(
                "select count(*) from delivery_outbox where channel='notification' and event_key like ?",
                Int::class.java,
                "generated-files:$id:%",
            ),
        )
        val notice = mapper.readTree(
            jdbc.queryForObject("select payload::text from delivery_outbox where event_key like ?", String::class.java, "generated-files:$id:%"),
        )
        assertEquals("FILES_READY", notice["email"]["kind"].asText())
        assertEquals("/client/applications/$id", notice["link"].asText())
        assertTrue(!notice.toString().contains("stored_name"))
        for (file in ready) {
            val fileId = file["id"].asText()
            val url = "/api/v1/applications/$id/artifacts/$fileId/download"
            fun download(token: String) = mockMvc.get(url) { header("Authorization", "Bearer $token") }.andReturn().response
            val first = download(client)
            assertEquals(200, first.status, first.contentAsString)
            assertEquals(file["checksum"].asText(), GeneratedArtifacts.checksum(first.contentAsByteArray))
            assertEquals(file["sizeBytes"].asLong(), first.contentAsByteArray.size.toLong())
            assertEquals(file["contentType"].asText(), first.contentType)
            assertTrue(first.getHeader("Content-Disposition")!!.startsWith("attachment;"))
            assertEquals("no-store", first.getHeader("Cache-Control"))
            assertContentEquals(first.contentAsByteArray, download(client).contentAsByteArray)
            assertEquals(200, download(manager).status)
            val stranger = token("CLIENT", UUID.randomUUID())
            assertEquals(404, download(stranger).status)
            assertEquals(404, post("/api/v1/applications/$id/artifacts/$fileId/retry", stranger).status)
            assertEquals(file, json(post("/api/v1/applications/$id/artifacts/$fileId/retry", client)))
        }
        assertEquals(404, list(token("CLIENT", UUID.randomUUID())).status)
        assertEquals(loan, get(id)["process"]["loan"])
    }

    @Test
    fun `a corrupted stored file is never downloaded and recovery keeps the issued loan and object key`() {
        val id = submitted()
        worker.runDue()
        post("/api/v1/applications/$id/offer/accept", client)
        post("/api/v1/applications/$id/sign", client, """{"code":"424242"}""")
        worker.runDue()
        val loan = get(id)["process"]["loan"]
        fun list() = json(mockMvc.get("/api/v1/applications/$id/artifacts") { header("Authorization", "Bearer $client") }.andReturn().response)
        val records = list()
        repeat(3) { artifacts.generateOne() }
        val fileId = UUID.fromString(records[0]["id"].asText())
        val storedName = jdbc.queryForObject("select stored_name from generated_artifacts where id=?", String::class.java, fileId)!!
        val corrupt = "Synthetic corrupted bytes".toByteArray()
        fileStore.put(storedName, corrupt.inputStream(), corrupt.size.toLong(), StorageBackend.LOCAL)
        val url = "/api/v1/applications/$id/artifacts/$fileId"
        val failed = mockMvc.get("$url/download") { header("Authorization", "Bearer $client") }.andReturn().response
        assertEquals(503, failed.status)
        assertTrue(json(failed)["message"].asText().contains("временно недоступен"))
        assertEquals("ERROR", list().first { it["id"].asText() == fileId.toString() }["status"].asText())
        assertEquals(200, post("$url/retry", client).status)
        assertTrue(artifacts.generateOne())
        assertEquals(storedName, jdbc.queryForObject("select stored_name from generated_artifacts where id=?", String::class.java, fileId))
        val restored = mockMvc.get("$url/download") { header("Authorization", "Bearer $client") }.andReturn().response
        assertEquals(200, restored.status)
        assertTrue(restored.contentAsByteArray.size > corrupt.size)
        assertEquals(3, list().size())
        assertEquals(loan, get(id)["process"]["loan"])
        assertEquals("DISBURSED", status(id))
    }

    @Test
    fun `competing artifact workers publish each final file once and queue a single readiness event`() {
        val id = submitted()
        worker.runDue()
        post("/api/v1/applications/$id/offer/accept", client)
        post("/api/v1/applications/$id/sign", client, """{"code":"424242"}""")
        worker.runDue()
        mockMvc.get("/api/v1/applications/$id/artifacts") { header("Authorization", "Bearer $client") }.andExpect { status { isOk() } }
        Executors.newFixedThreadPool(4).use { executor ->
            val gate = CountDownLatch(1)
            val jobs = (1..4).map {
                executor.submit(
                    Callable {
                        gate.await()
                        artifacts.generateOne()
                    },
                )
            }
            gate.countDown()
            assertEquals(3, jobs.count { it.get(30, TimeUnit.SECONDS) })
            val notices = (1..2).map { executor.submit(Callable { artifacts.notifyReadyGroups() }) }
            notices.forEach { it.get(30, TimeUnit.SECONDS) }
        }
        assertEquals(
            3,
            jdbc.queryForObject("select count(*) from generated_artifacts where application_id=?::uuid and status='READY' and attempts=1", Int::class.java, id),
        )
        assertEquals(1, jdbc.queryForObject("select count(*) from generated_artifact_notices where application_id=?::uuid", Int::class.java, id))
        assertEquals(1, jdbc.queryForObject("select count(*) from delivery_outbox where event_key like ?", Int::class.java, "generated-files:$id:%"))
        assertEquals("DISBURSED", status(id))
    }

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
        assertTrue(!card["sourceFetchedAt"].has("BUREAU"), "No receipt time may be invented for an unavailable source")
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
