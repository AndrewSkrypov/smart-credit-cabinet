package ru.creditcabinet.chat

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.reset
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.application.domain.CompanyDirectory
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.common.notifications.PlatformNotifications
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.pipeline.FakeSources
import ru.creditcabinet.pipeline.MutableClock
import ru.creditcabinet.pipeline.PipelineFakesConfig
import ru.creditcabinet.pipeline.PipelineWorker
import java.time.Duration
import java.util.Date
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(PipelineFakesConfig::class)
class ChatIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var tx: TransactionTemplate

    @Autowired private lateinit var clock: MutableClock

    @Autowired private lateinit var sources: FakeSources

    @Autowired private lateinit var worker: PipelineWorker

    @MockitoBean private lateinit var companies: CompanyDirectory

    @MockitoSpyBean private lateinit var notifications: PlatformNotifications
    private val companyId = UUID.randomUUID()
    private val actorId = UUID.randomUUID()
    private val inn = "00" + UUID.randomUUID().leastSignificantBits.toString().filter(Char::isDigit).take(8)
    private val client by lazy { token("CLIENT", companyId, actorId) }
    private val colleague by lazy { token("CLIENT", companyId) }
    private val foreign by lazy { token("CLIENT", UUID.randomUUID()) }
    private val underwriter by lazy { token("UNDERWRITER") }
    private val manager by lazy { token("MANAGER") }
    private val risk by lazy { token("RISK_ADMIN") }
    private val pdf = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".toByteArray()

    @BeforeTest fun setup() {
        jdbc.update("update pipeline_jobs set status='CANCELLED' where status in ('PENDING','RUNNING')")
        whenever(companies.find(any())).thenAnswer { CompanySnapshot(it.getArgument(0), inn, "ИП Тест", LegalForm.ENTREPRENEUR) }
        sources.companies[inn] = FakeSources.healthy(inn, clock.instant().atZone(java.time.ZoneOffset.UTC).toLocalDate(), okved = "41.20")
    }

    private fun token(role: String, company: UUID? = null, actor: UUID = UUID.randomUUID()) = Jwts.builder().subject(actor.toString())
        .claim(TokenClaims.ROLE, role).claim(TokenClaims.FULL_NAME, "Участник переписки").claim(TokenClaims.CAN_SIGN, false)
        .apply { company?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
        .expiration(Date(System.currentTimeMillis() + 3_600_000)).signWith(verifier.signingKey).compact()
    private fun json(response: MockHttpServletResponse): JsonNode = mapper.readTree(response.getContentAsString(Charsets.UTF_8))
    private fun get(path: String, token: String? = client) = mvc.get(path) { token?.let { header("Authorization", "Bearer $it") } }.andReturn().response
    private fun post(path: String, body: Any? = null, token: String? = client, key: UUID? = null) = mvc.post(path) {
        token?.let { header("Authorization", "Bearer $it") }
        key?.let { header("Idempotency-Key", it) }
        if (body != null) {
            contentType = MediaType.APPLICATION_JSON
            content = mapper.writeValueAsString(body)
        }
    }.andReturn().response
    private fun draft(product: String = "WORKING_CAPITAL", purpose: String = "WORKING_CAPITAL"): String {
        val response = post(
            "/api/v1/applications",
            mapOf(
                "productCode" to product,
                "purpose" to purpose,
                "amount" to 1_000_000,
                "termMonths" to 12,
                "declaredRevenue12m" to 14_400_000,
                "declaredMonthlyDebtPayments" to 30000,
                "bureauConsent" to true,
            ),
        )
        assertEquals(201, response.status, response.contentAsString)
        return json(response)["id"].asText()
    }
    private fun submitted(product: String = "WORKING_CAPITAL", purpose: String = "WORKING_CAPITAL"): String = draft(product, purpose).also {
        assertEquals(200, post("/api/v1/applications/$it/submit").status)
    }
    private fun manual(): String = submitted().also {
        worker.runDue()
        assertEquals("MANUAL_REVIEW", json(get("/api/v1/applications/$it"))["status"].asText())
    }
    private fun send(id: String, text: String = "Сообщение", token: String? = client, key: UUID = UUID.randomUUID(), files: List<String> = emptyList()) =
        post("/api/v1/applications/$id/messages", mapOf("text" to text, "documentIds" to files), token, key)
    private fun upload(id: String, token: String = client, key: UUID = UUID.randomUUID(), name: String = "synthetic.pdf", bytes: ByteArray = pdf) =
        mvc.multipart("/api/v1/applications/$id/messages/attachments") {
            header("Authorization", "Bearer $token")
            header("Idempotency-Key", key)
            file(MockMultipartFile("file", name, "application/pdf", bytes))
        }.andReturn().response
    private fun count(table: String, id: String): Long {
        require(table in setOf("application_messages", "application_chat_audit", "documents", "application_events"))
        return jdbc.queryForObject("select count(*) from $table where application_id=?", Long::class.java, UUID.fromString(id))!!
    }
    private fun unread(id: String, token: String): Long = json(get("/api/v1/applications/$id/messages", token))["unreadCount"].asLong()
    private fun read(id: String, seq: Long, token: String = client) = post("/api/v1/applications/$id/messages/read", mapOf("lastReadSeq" to seq), token)

    @Test fun `literal round trip is atomic, idempotent and independent from the credit journal`() {
        val id = submitted()
        val before = json(get("/api/v1/applications/$id"))
        val events = json(get("/api/v1/applications/$id/events"))
        val key = UUID.randomUUID()
        val text = "План: 5 < 10. <b>Это простой текст</b>\nБез разметки **и ссылок**"
        val first = send(id, text, key = key)
        assertEquals(200, first.status, first.contentAsString)
        assertEquals(text, json(first)["text"].asText())
        assertFalse(json(first).has("payloadHash"))
        assertFalse(json(first).has("key"))
        assertEquals(json(first), json(send(id, text, key = key)))
        assertEquals(409, send(id, "Другой текст", key = key).status)
        assertEquals(1, count("application_messages", id))
        assertEquals(1, count("application_chat_audit", id))
        val outbox = jdbc.queryForObject(
            "select count(*) from delivery_outbox where event_key like ?",
            Long::class.java,
            "chat:${json(first)["id"].asText()}:%",
        )
        assertEquals(3, outbox)
        assertEquals(1, unread(id, underwriter))
        assertEquals(1, unread(id, manager))
        assertEquals(0, unread(id, client))
        val bankList = json(get("/api/v1/applications", underwriter)).first { it["id"].asText() == id }
        assertEquals(1, bankList["unreadChatCount"].asLong())
        assertEquals(200, read(id, 1, underwriter).status)
        assertEquals(0, unread(id, risk), "reading is shared by the bank side")
        assertEquals(200, send(id, "Получили план. Ответ банка", underwriter).status)
        assertEquals(1, unread(id, client))
        assertEquals(200, read(id, 2, colleague).status)
        assertEquals(0, unread(id, client), "all company representatives share a cursor")
        val after = json(get("/api/v1/applications/$id"))
        assertEquals(before["status"], after["status"])
        assertEquals(before["version"], after["version"])
        assertEquals(events, json(get("/api/v1/applications/$id/events")))
        assertTrue(json(get("/api/v1/applications/$id/messages/audit", risk)).any { it["action"].asText() == "MESSAGE_SENT" })
        assertEquals(403, get("/api/v1/applications/$id/messages/audit").status)
        assertFails { jdbc.update("update application_messages set message_text='changed' where application_id=?", UUID.fromString(id)) }
        assertFails { jdbc.update("delete from application_chat_audit where application_id=?", UUID.fromString(id)) }
    }

    @Test fun `the application visibility applies to all bank roles, foreign companies, drafts and terminal states`() {
        val id = draft()
        for (bank in listOf(manager, underwriter, risk)) {
            assertEquals(404, get("/api/v1/applications/$id/messages", bank).status)
            assertEquals(404, send(id, token = bank).status)
            assertEquals(404, upload(id, bank).status)
        }
        assertEquals(200, get("/api/v1/applications/$id/messages").status)
        assertEquals(409, send(id).status)
        assertEquals(409, upload(id).status)
        assertEquals(401, get("/api/v1/applications/$id/messages", null).status)
        assertEquals(401, send(id, token = null).status)
        assertEquals(200, post("/api/v1/applications/$id/submit").status)
        assertEquals(404, get("/api/v1/applications/$id/messages", foreign).status)
        assertEquals(404, send(id, token = foreign).status)
        assertEquals(404, read(id, 0, foreign).status)
        assertEquals(404, upload(id, foreign).status)
        for (bank in listOf(manager, underwriter, risk)) assertEquals(200, send(id, token = bank).status)
        val key = UUID.randomUUID()
        val sent = send(id, "Сообщение без права подписи", key = key)
        assertEquals(200, sent.status)
        assertEquals(200, post("/api/v1/applications/$id/withdraw").status)
        assertEquals(200, get("/api/v1/applications/$id/messages", underwriter).status)
        assertEquals(409, send(id, token = underwriter).status)
        assertEquals(409, upload(id, underwriter).status)
        assertEquals(200, read(id, 4, underwriter).status)
        assertEquals(json(sent), json(send(id, "Сообщение без права подписи", key = key)), "a committed retry after closure is a read")
        assertFalse(json(get("/api/v1/applications/$id/messages"))["writable"].asBoolean())
    }

    @Test fun `pending files stay private, published attachments share the protected document download and cannot be rewritten`() {
        val id = submitted()
        val events = json(get("/api/v1/applications/$id/events"))
        val key = UUID.randomUUID()
        val uploaded = upload(id, key = key)
        assertEquals(200, uploaded.status, uploaded.contentAsString)
        val file = json(uploaded)["id"].asText()
        assertEquals(json(uploaded), json(upload(id, key = key)))
        assertEquals(409, upload(id, key = key, name = "different.pdf").status)
        assertEquals(1, count("documents", id))
        assertEquals(0, json(get("/api/v1/applications/$id"))["documents"].size())
        assertEquals(200, get("/api/v1/applications/$id/documents/$file").status)
        assertEquals(404, get("/api/v1/applications/$id/documents/$file", underwriter).status)
        assertEquals(404, send(id, token = underwriter, files = listOf(file)).status)
        assertEquals(200, send(id, text = "", files = listOf(file)).status)
        val downloaded = get("/api/v1/applications/$id/documents/$file", underwriter)
        assertEquals(200, downloaded.status)
        assertTrue(pdf.contentEquals(downloaded.contentAsByteArray))
        val bankFile = json(upload(id, underwriter))["id"].asText()
        assertEquals(404, get("/api/v1/applications/$id/documents/$bankFile").status)
        assertEquals(200, send(id, token = underwriter, files = listOf(bankFile)).status)
        assertEquals(200, get("/api/v1/applications/$id/documents/$bankFile").status)
        assertEquals(events, json(get("/api/v1/applications/$id/events")))
        assertEquals(0, json(get("/api/v1/applications/$id"))["documents"].size())
        assertFails { jdbc.update("update documents set file_name='replaced.pdf' where id=?", UUID.fromString(file)) }
        assertFails { jdbc.update("update documents set deleted_at=now() where id=?", UUID.fromString(file)) }
        assertFails { jdbc.update("delete from message_attachments where document_id=?", UUID.fromString(file)) }
    }

    @Test fun `chat files do not fulfil a bank document request and an existing business document may be referenced`() {
        val id = manual()
        assertEquals(
            200,
            post("/api/v1/underwriting/applications/$id/request-info", mapOf("items" to listOf(mapOf("documentType" to "OTHER"))), manager).status,
        )
        val events = json(get("/api/v1/applications/$id/events"))
        val chatFile = json(upload(id))["id"].asText()
        assertEquals(200, send(id, files = listOf(chatFile)).status)
        assertEquals(events, json(get("/api/v1/applications/$id/events")))
        assertEquals(400, post("/api/v1/applications/$id/info-provided").status)
        val business = mvc.multipart("/api/v1/applications/$id/documents") {
            header("Authorization", "Bearer $client")
            param("type", "OTHER")
            file(MockMultipartFile("file", "business.pdf", "application/pdf", pdf))
        }.andReturn().response
        assertEquals(201, business.status, business.contentAsString)
        val document = json(business)["id"].asText()
        assertEquals(200, send(id, token = risk, files = listOf(document)).status)
        assertEquals(200, post("/api/v1/applications/$id/info-provided").status)
        assertEquals(1, json(get("/api/v1/applications/$id"))["documents"].size())
        val foreignFile = UUID.randomUUID().toString()
        assertEquals(404, send(id, files = listOf(foreignFile)).status)
    }

    @Test fun `concurrent retries have one effect and concurrent readers never move a side cursor backwards`() {
        val id = submitted()
        val key = UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(2)
        val start = CountDownLatch(1)
        try {
            val futures = (1..2).map {
                pool.submit<MockHttpServletResponse> {
                    start.await()
                    send(id, key = key)
                }
            }
            start.countDown()
            val results = futures.map { it.get(20, TimeUnit.SECONDS) }
            assertTrue(results.all { it.status == 200 })
            assertEquals(json(results[0]), json(results[1]))
            assertEquals(1, count("application_messages", id))
            assertEquals(1, count("application_chat_audit", id))
            assertEquals(200, send(id, "Второе").status)
            assertEquals(200, send(id, "Третье").status)
            val reads = listOf(pool.submit<MockHttpServletResponse> { read(id, 3, underwriter) }, pool.submit<MockHttpServletResponse> { read(id, 1, manager) })
            assertTrue(reads.map { it.get(20, TimeUnit.SECONDS) }.all { it.status == 200 })
            assertEquals(3, json(get("/api/v1/applications/$id/messages", risk))["readSeq"].asLong())
            assertEquals(0, unread(id, risk))
            assertEquals(400, read(id, 4, underwriter).status)
            assertFails { jdbc.update("update application_chat_read_cursors set last_read_seq=0 where application_id=?", UUID.fromString(id)) }
        } finally {
            pool.shutdownNow()
        }
    }

    @Test fun `message validation and sequence pagination preserve literal text and file semantics`() {
        val id = submitted()
        assertEquals(400, send(id, "").status)
        assertEquals(400, send(id, " ").status)
        assertEquals(400, send(id, "a".repeat(2001)).status)
        assertEquals(400, send(id, "a\u0000b").status)
        assertEquals(400, send(id, files = (1..6).map { UUID.randomUUID().toString() }).status)
        val missing = UUID.randomUUID().toString()
        assertEquals(400, send(id, files = listOf(missing, missing)).status)
        for (i in 1..6) assertEquals(200, send(id, "Сообщение $i").status)
        val base = "/api/v1/applications/$id/messages"
        assertEquals(listOf(5L, 6L), json(get("$base?limit=2"))["messages"].map { it["seq"].asLong() })
        assertEquals(listOf(2L, 3L), json(get("$base?before=4&limit=2"))["messages"].map { it["seq"].asLong() })
        assertEquals(listOf(3L, 4L), json(get("$base?after=2&limit=2"))["messages"].map { it["seq"].asLong() })
        assertEquals(400, get("$base?after=1&before=5").status)
        assertEquals(400, get("$base?after=-1").status)
        assertEquals(400, get("$base?limit=101").status)
    }

    @Test fun `the shared message window spans applications and reads do not consume it`() {
        val first = submitted()
        val second = submitted("OVERDRAFT", "CASH_GAP")
        repeat(30) { assertEquals(200, send(if (it % 2 == 0) first else second).status) }
        assertEquals(429, send(first).status)
        repeat(3) { assertEquals(200, get("/api/v1/applications/$first/messages").status) }
        assertEquals(200, send(first, token = colleague).status)
        clock.advance(Duration.ofSeconds(61))
        assertEquals(200, send(first).status)
    }

    @Test fun `an outbox failure rolls back the message, audit, attachments and sequence`() {
        val id = submitted()
        val key = UUID.randomUUID()
        val file = json(upload(id))["id"].asText()
        doThrow(IllegalStateException("Synthetic notification failure")).whenever(notifications).send(any())
        try {
            assertEquals(500, send(id, key = key, files = listOf(file)).status)
        } finally {
            reset(notifications)
        }
        assertEquals(0, count("application_messages", id))
        assertEquals(0, json(get("/api/v1/applications/$id/messages"))["lastSeq"].asLong())
        assertEquals(1, count("application_chat_audit", id), "only the earlier committed file upload remains")
        assertEquals(200, send(id, key = key, files = listOf(file)).status)
        assertEquals(1, count("application_messages", id))
    }

    @Test fun `real foreign and deleted file ids cannot be bound to a message`() {
        val id = draft()
        val uploaded = mvc.multipart("/api/v1/applications/$id/documents") {
            header("Authorization", "Bearer $client")
            param("type", "OTHER")
            file(MockMultipartFile("file", "removed.pdf", "application/pdf", pdf))
        }.andReturn().response
        assertEquals(201, uploaded.status)
        val removed = json(uploaded)["id"].asText()
        jdbc.update("update documents set deleted_at=now() where id=?", UUID.fromString(removed))
        assertEquals(200, post("/api/v1/applications/$id/submit").status)
        assertEquals(404, send(id, files = listOf(removed)).status)
        val other = submitted("OVERDRAFT", "CASH_GAP")
        val foreignFile = json(upload(other))["id"].asText()
        assertEquals(404, send(id, files = listOf(foreignFile)).status)
        val message = json(send(id))["id"].asText()
        assertFails {
            jdbc.update(
                "insert into message_attachments(message_id,application_id,document_id) values (?,?,?)",
                UUID.fromString(message),
                UUID.fromString(id),
                UUID.fromString(foreignFile),
            )
        }
    }

    @Test fun `a message waiting on a committed closure sees the terminal status and cannot slip through`() {
        val id = submitted()
        val pool = Executors.newFixedThreadPool(2)
        val locked = CountDownLatch(1)
        val finish = CountDownLatch(1)
        try {
            val closer = pool.submit {
                tx.executeWithoutResult {
                    jdbc.queryForObject("select version from applications where id=? for update", Long::class.java, UUID.fromString(id))
                    locked.countDown()
                    assertTrue(finish.await(20, TimeUnit.SECONDS))
                    jdbc.update("update applications set status='WITHDRAWN',version=version+1 where id=?", UUID.fromString(id))
                }
            }
            assertTrue(locked.await(10, TimeUnit.SECONDS))
            val sender = pool.submit<MockHttpServletResponse> { send(id) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            var waiting = false
            while (System.nanoTime() < deadline) {
                waiting =
                    jdbc.queryForObject(
                        "select count(*) from pg_stat_activity where wait_event_type='Lock' and query like '%applications%for share%'",
                        Int::class.java,
                    )!! >
                    0
                if (waiting) break
                Thread.sleep(10)
            }
            assertTrue(waiting, "the test must observe the actual database lock wait")
            finish.countDown()
            closer.get(10, TimeUnit.SECONDS)
            assertEquals(409, sender.get(10, TimeUnit.SECONDS).status)
            assertEquals(0, count("application_messages", id))
        } finally {
            finish.countDown()
            pool.shutdownNow()
        }
    }
}
