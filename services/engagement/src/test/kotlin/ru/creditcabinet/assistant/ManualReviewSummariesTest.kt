package ru.creditcabinet.assistant

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterAll
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import ru.creditcabinet.common.outbox.OutboxWorker
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.tokenFor
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(properties = ["cabinet.outbox.worker-enabled=false"])
@AutoConfigureMockMvc
class ManualReviewSummariesTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var jwt: JwtVerifier

    @Autowired private lateinit var outbox: OutboxWorker

    @Autowired private lateinit var jdbc: JdbcTemplate

    @MockitoBean private lateinit var model: LanguageModel

    @MockitoBean private lateinit var cache: ManualReviewSummaryCache

    private val applicationId = UUID.randomUUID()
    private val event = ManualReviewEvent(1, UUID.randomUUID(), applicationId)
    private val previousRevision = "a".repeat(64)
    private val nextRevision = "b".repeat(64)
    private val underwriter by lazy { jwt.tokenFor(UUID.randomUUID(), "UNDERWRITER") }

    @Test
    fun `delivery is durable and idempotent and a later staff request reads the warmed text`() {
        val advice = advice("Рекомендована дополнительная проверка", previousRevision)
        val expected = "Рекомендована дополнительная проверка\n— Источник. Сверить данные."
        creditReplies[internalPath()] = StubReply(200, mapper.writeValueAsString(advice))
        whenever(cache.enabled).thenReturn(true)
        whenever(cache.publish(applicationId, null, event.eventId, previousRevision, expected)).thenReturn(true)

        assertEquals(204, accept(event))
        assertEquals(204, accept(event))
        val queued = jdbc.queryForObject(
            "select count(*) from delivery_outbox where event_key=? and channel=?",
            Int::class.java,
            event.eventId.toString(),
            ManualReviewSummaries.CHANNEL,
        )
        assertEquals(1, queued)
        assertEquals("PENDING", deliveryState())
        assertTrue(callsFor(internalPath()).isEmpty())
        outbox.runDue()
        assertEquals("DELIVERED", deliveryState())
        verify(cache).publish(applicationId, null, event.eventId, previousRevision, expected)
        assertEquals(listOf(false), callsFor(internalPath()).map { it.hasAuthorization })

        whenever(cache.eventFor(applicationId)).thenReturn(event.eventId)
        whenever(cache.text(applicationId, previousRevision)).thenReturn(expected)
        advice.remove("adviceRevision")
        creditReplies[publicPath()] = StubReply(200, mapper.writeValueAsString(advice))
        val summary = summary(underwriter)
        assertEquals(200, summary.status)
        val result = mapper.readTree(summary.getContentAsString(Charsets.UTF_8))
        assertEquals(expected, result["text"].asText())
        assertEquals("RULES", result["mode"].asText())
        assertEquals(listOf(true), callsFor(publicPath()).map { it.hasAuthorization })
        assertEquals(listOf(false, false), callsFor(internalPath()).map { it.hasAuthorization })
        verify(model, never()).chat(any())
    }

    @Test
    fun `a stale transition is acknowledged without publishing a summary`() {
        creditReplies[internalPath()] = StubReply(409)
        whenever(cache.enabled).thenReturn(true)
        assertEquals(204, accept(event))
        outbox.runDue()
        assertEquals("DELIVERED", deliveryState())
        assertEquals(1, callsFor(internalPath()).size)
        verify(cache, never()).publish(any(), anyOrNull(), any(), any(), any())
        verify(model, never()).chat(any())
    }

    @Test
    fun `a transient Credit failure leaves the event available for retry`() {
        creditReplies[internalPath()] = StubReply(503)
        whenever(cache.enabled).thenReturn(true)
        assertEquals(204, accept(event))
        outbox.runDue()
        assertEquals("PENDING", deliveryState())
        verify(cache, never()).publish(any(), anyOrNull(), any(), any(), any())

        creditReplies[internalPath()] = StubReply(200, mapper.writeValueAsString(advice("Проверить данные", previousRevision)))
        whenever(cache.publish(applicationId, null, event.eventId, previousRevision, "Проверить данные\n— Источник. Сверить данные.")).thenReturn(true)
        jdbc.update("update delivery_outbox set next_attempt_at=now() where event_key=?", event.eventId.toString())
        outbox.runDue()
        assertEquals("DELIVERED", deliveryState())
        assertEquals(2, callsFor(internalPath()).size)
    }

    @Test
    fun `a changed advice revision is rebuilt from the authorized current facts`() {
        val changed = "Изменился вывод"
        val fresh = advice(changed, nextRevision)
        creditReplies[internalPath()] = StubReply(200, mapper.writeValueAsString(fresh))
        fresh.remove("adviceRevision")
        creditReplies[publicPath()] = StubReply(200, mapper.writeValueAsString(fresh))
        whenever(cache.enabled).thenReturn(true)
        whenever(cache.eventFor(applicationId)).thenReturn(event.eventId)
        val expected = "$changed\n— Источник. Сверить данные."
        whenever(cache.publish(applicationId, event.eventId, event.eventId, nextRevision, expected)).thenReturn(true)

        val user = summary(underwriter)
        assertEquals(200, user.status)
        assertEquals(expected, mapper.readTree(user.getContentAsString(Charsets.UTF_8))["text"].asText())
        verify(cache, never()).text(applicationId, previousRevision)
        verify(cache).publish(applicationId, event.eventId, event.eventId, nextRevision, expected)
        verify(model, never()).chat(any())
    }

    @Test
    fun `a second entry replaces the transition and replay of the first event adds no work`() {
        val nextEvent = event.copy(eventId = UUID.randomUUID())
        val previous = "Первый вывод\n— Источник. Сверить данные."
        val current = "Новый вывод\n— Источник. Сверить данные."
        creditReplies[internalPath()] = StubReply(200, mapper.writeValueAsString(advice("Первый вывод", previousRevision)))
        creditReplies[internalPath(nextEvent)] = StubReply(200, mapper.writeValueAsString(advice("Новый вывод", nextRevision)))
        whenever(cache.enabled).thenReturn(true)
        whenever(cache.publish(applicationId, null, event.eventId, previousRevision, previous)).thenReturn(true)

        assertEquals(204, accept(event))
        outbox.runDue()
        assertEquals("DELIVERED", deliveryState())
        whenever(cache.eventFor(applicationId)).thenReturn(event.eventId)
        whenever(cache.publish(applicationId, event.eventId, nextEvent.eventId, nextRevision, current)).thenReturn(true)
        creditReplies[internalPath()] = StubReply(409)

        assertEquals(204, accept(nextEvent))
        assertEquals(204, accept(event))
        outbox.runDue()
        assertEquals("DELIVERED", deliveryState(nextEvent))
        assertEquals(1, callsFor(internalPath()).size)
        assertEquals(1, callsFor(internalPath(nextEvent)).size)
        verify(cache).publish(applicationId, event.eventId, nextEvent.eventId, nextRevision, current)
    }

    @Test
    fun `unsupported versions fail and the event route stays out of the public OpenAPI`() {
        assertEquals(400, accept(event.copy(schemaVersion = 2)))
        assertEquals(0, jdbc.queryForObject("select count(*) from delivery_outbox where event_key=?", Int::class.java, event.eventId.toString()))
        val docs = mvc.get("/v3/api-docs/engagement").andReturn().response
        assertEquals(200, docs.status)
        assertFalse(mapper.readTree(docs.contentAsByteArray)["paths"].has(EVENT_PATH))
    }

    @Test
    fun `customers cannot read a warmed underwriter summary`() {
        val customer = jwt.tokenFor(UUID.randomUUID(), "CLIENT", UUID.randomUUID())
        assertEquals(403, summary(customer).status)
        assertTrue(callsFor(publicPath()).isEmpty())
        assertTrue(callsFor(internalPath()).isEmpty())
        verify(cache, never()).eventFor(applicationId)
    }

    private fun accept(value: ManualReviewEvent) = mvc.post(EVENT_PATH) {
        contentType = MediaType.APPLICATION_JSON
        content = mapper.writeValueAsString(value)
    }.andReturn().response.status

    private fun summary(token: String) = mvc.post("/api/v1/assistant/underwriting-summary") {
        header("Authorization", "Bearer $token")
        contentType = MediaType.APPLICATION_JSON
        content = mapper.writeValueAsString(mapOf("applicationId" to applicationId))
    }.andReturn().response

    private fun deliveryState(value: ManualReviewEvent = event) =
        jdbc.queryForObject("select state from delivery_outbox where event_key=?", String::class.java, value.eventId.toString())

    private fun internalPath(value: ManualReviewEvent = event) = "/internal/v1/underwriting/applications/$applicationId/advice?eventId=${value.eventId}"

    private fun publicPath() = "/api/v1/underwriting/applications/$applicationId/advice"

    private fun advice(verdict: String, revision: String) = mapper.createObjectNode().apply {
        put("applicationId", applicationId.toString())
        put("verdict", verdict)
        put("modelVersion", 1)
        putArray("items").addObject().put("title", "Источник").put("text", "Сверить данные.")
        put("adviceRevision", revision)
    }

    private fun callsFor(path: String) = creditCalls.filter { it.path == path }

    private data class StubReply(val status: Int, val body: String = "")

    private data class CreditCall(val path: String, val hasAuthorization: Boolean)

    companion object {
        private const val EVENT_PATH = "/internal/v1/assistant/manual-review-events"
        private val creditReplies = ConcurrentHashMap<String, StubReply>()
        private val creditCalls = CopyOnWriteArrayList<CreditCall>()
        private val credit = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.use {
                    val path = exchange.requestURI.toString()
                    creditCalls.add(CreditCall(path, exchange.requestHeaders.containsKey("Authorization")))
                    val reply = creditReplies[path] ?: StubReply(404)
                    val bytes = reply.body.toByteArray(Charsets.UTF_8)
                    exchange.responseHeaders.set("Content-Type", "application/json")
                    exchange.sendResponseHeaders(reply.status, bytes.size.toLong())
                    exchange.responseBody.use { body -> body.write(bytes) }
                }
            }
            start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun creditLocation(registry: DynamicPropertyRegistry) {
            registry.add("cabinet.credit.url") { "http://127.0.0.1:${credit.address.port}" }
        }

        @JvmStatic
        @AfterAll
        fun stopCredit() = credit.stop(0)
    }
}
