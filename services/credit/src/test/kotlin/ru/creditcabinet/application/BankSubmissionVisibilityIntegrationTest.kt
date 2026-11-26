package ru.creditcabinet.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import io.jsonwebtoken.Jwts
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.analytics.infrastructure.HttpCurrentStatusesProjection
import ru.creditcabinet.analytics.infrastructure.JdbcAnalyticsQueries
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import java.net.InetSocketAddress
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest(properties = ["cabinet.pipeline.enabled=false", "cabinet.outbox.worker-enabled=false"])
@AutoConfigureMockMvc
@Transactional
class BankSubmissionVisibilityIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var mapper: ObjectMapper

    @Test
    fun `a pre-submission withdrawal is absent from bank cards queues analytics and chat`() {
        val bank = token(Roles.UNDERWRITER)
        val before = read("/api/v1/analytics/funnel", bank)["currentStatuses"].path("WITHDRAWN").asInt()
        val name = "Visibility-${UUID.randomUUID()}"
        val beforeSubmission = seed("WITHDRAWN", name = name)
        val afterSubmission = seed("WITHDRAWN", name = name, submitted = true)

        for (path in listOf(
            "/api/v1/applications/$beforeSubmission",
            "/api/v1/applications/$beforeSubmission/events",
            "/api/v1/applications/$beforeSubmission/messages",
            "/api/v1/applications/$beforeSubmission/messages/audit",
            "/api/v1/underwriting/applications/$beforeSubmission",
        )) {
            read(path, bank, expected = 404)
        }
        read("/api/v1/applications/$afterSubmission", bank)
        read("/api/v1/applications/$afterSubmission/messages", bank)
        read("/api/v1/underwriting/applications/$afterSubmission", bank)
        val fixtureIds = setOf(beforeSubmission.toString(), afterSubmission.toString())
        val queue = read("/api/v1/applications?status=WITHDRAWN", bank)
        assertEquals(listOf(afterSubmission.toString()), queue.map { it["id"].asText() }.filter { it in fixtureIds })
        val page = read("/api/v1/applications/page?group=done&q=$name", bank)
        assertEquals(1, page["countsByGroup"]["done"].asInt())
        assertEquals(listOf(afterSubmission.toString()), page["items"].map { it["id"].asText() })
        assertEquals(before + 1, read("/api/v1/analytics/funnel", bank)["currentStatuses"]["WITHDRAWN"].asInt())
    }

    @Test
    fun `postgres visibility selects SQL for a mixed withdrawal population and projection for a matching one`() {
        jdbc.execute("create temporary table applications (id uuid, status text, submitted_at timestamptz) on commit drop")
        jdbc.execute("create temporary table application_status_outbox (stream_generation text, state text) on commit drop")
        jdbc.update(
            """
            insert into pg_temp.applications (id, status, submitted_at) values
                (gen_random_uuid(), 'DRAFT', null),
                (gen_random_uuid(), 'PENDING_CLIENT_CONFIRMATION', null),
                (gen_random_uuid(), 'WITHDRAWN', null),
                (gen_random_uuid(), 'WITHDRAWN', current_timestamp),
                (gen_random_uuid(), 'SUBMITTED', current_timestamp)
            """.trimIndent(),
        )

        val calls = AtomicInteger()
        val peer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        peer.createContext("/internal/v1/analytics/current-statuses") { request ->
            calls.incrementAndGet()
            val body = """{"generation":"visibility-test","currentStatuses":{"SUBMITTED":1,"WITHDRAWN":1}}""".toByteArray()
            request.sendResponseHeaders(200, body.size.toLong())
            request.responseBody.use { it.write(body) }
        }
        peer.start()
        val namedJdbc = NamedParameterJdbcTemplate(jdbc)
        val metrics = SimpleMeterRegistry()
        val projection = HttpCurrentStatusesProjection(
            namedJdbc,
            mapper,
            metrics,
            enabled = true,
            captureEnabled = true,
            workerEnabled = true,
            generation = "visibility-test",
            url = "http://127.0.0.1:${peer.address.port}",
            token = "synthetic-analytics-test-only-token",
            timeoutMs = 2_000,
        )
        try {
            val analytics = JdbcAnalyticsQueries(namedJdbc)
            val to = Instant.now()
            val from = to.minusSeconds(86_400)
            val expected = mapOf("SUBMITTED" to 1, "WITHDRAWN" to 1)
            val mixedPopulation = projection.readWhenReady()
            assertNull(mixedPopulation)
            assertEquals(0, calls.get())
            assertEquals(expected, analytics.read(from, to, mixedPopulation).currentStatuses)
            val reads = "cabinet.analytics.current.statuses.read"
            assertEquals(1.0, metrics.get(reads).tags("source", "sql", "reason", "submission_visibility").counter().count())

            jdbc.update("delete from pg_temp.applications where status = 'WITHDRAWN' and submitted_at is null")
            assertEquals(expected, projection.readWhenReady())
            assertEquals(expected, analytics.read(from, to, null).currentStatuses)
            assertEquals(1, calls.get())
            assertEquals(1.0, metrics.get(reads).tags("source", "projection", "reason", "ready").counter().count())
        } finally {
            projection.close()
            metrics.close()
            peer.stop(0)
        }
    }

    @Test
    fun `a conversation before submission sends no bank notice`() {
        val company = UUID.randomUUID()
        val pending = seed("PENDING_CLIENT_CONFIRMATION", company = company, agent = UUID.randomUUID(), handedOff = true)
        val submitted = seed("SUBMITTED", company = company, product = "OVERDRAFT", submitted = true)
        val client = token(Roles.CLIENT, company)
        for (application in listOf(pending, submitted)) {
            val result = mvc.post("/api/v1/applications/$application/messages") {
                header("Authorization", "Bearer $client")
                header("Idempotency-Key", UUID.randomUUID().toString())
                contentType = MediaType.APPLICATION_JSON
                content = """{"text":"Уточните условия"}"""
            }.andReturn().response
            assertEquals(200, result.status, result.contentAsString)
        }
        fun bankNotices(application: UUID) = jdbc.queryForObject(
            "select count(*) from delivery_outbox where payload ->> 'link' = ? and payload ->> 'type' = 'APPLICATION_MESSAGE'",
            Int::class.java,
            "/bank/applications/$application",
        )
        assertEquals(0, bankNotices(pending))
        assertEquals(3, bankNotices(submitted))
        read("/api/v1/applications/$pending/messages", token(Roles.MANAGER), expected = 404)
    }

    @Test
    fun `client page counts returned drafts and preserves private agent drafts`() {
        val company = UUID.randomUUID()
        val agent = UUID.randomUUID()
        val returned = seed("DRAFT", company = company, agent = agent, handedOff = true)
        val privateDraft = seed("DRAFT", company = company, agent = agent, product = "OVERDRAFT")
        val withdrawnPrivate = seed("WITHDRAWN", company = company, agent = agent)
        val client = token(Roles.CLIENT, company)
        read("/api/v1/applications/$privateDraft", client, expected = 404)
        read("/api/v1/applications/$withdrawnPrivate", client, expected = 404)
        read("/api/v1/applications/$returned", client)
        val page = read("/api/v1/applications/page?group=active", client)
        assertEquals(1, page["totalElements"].asInt())
        assertEquals(1, page["actionRequiredCount"].asInt())
        assertEquals(listOf(returned.toString()), page["items"].map { it["id"].asText() })
        assertEquals(listOf(returned.toString()), page["actionRequiredPreview"].map { it["id"].asText() })
        assertEquals(0, read("/api/v1/applications/page?group=done", client)["totalElements"].asInt())
    }

    @Test
    fun `client pages and detail recognise pending and submitted applications written by an older image`() {
        val company = UUID.randomUUID()
        val agent = UUID.randomUUID()
        val pending = seed("PENDING_CLIENT_CONFIRMATION", company = company, agent = agent)
        val submitted = seed("SUBMITTED", company = company, agent = agent, product = "OVERDRAFT", submitted = true)
        val client = token(Roles.CLIENT, company)

        read("/api/v1/applications/$pending", client)
        read("/api/v1/applications/$submitted", client)
        val page = read("/api/v1/applications/page?group=active", client)
        assertEquals(2, page["totalElements"].asInt())
        assertEquals(setOf(pending.toString(), submitted.toString()), page["items"].map { it["id"].asText() }.toSet())
        assertEquals(listOf(pending.toString()), page["actionRequiredPreview"].map { it["id"].asText() })
    }

    private fun seed(
        status: String,
        name: String = "Синтетическая компания",
        company: UUID = UUID.randomUUID(),
        agent: UUID? = null,
        product: String = "WORKING_CAPITAL",
        submitted: Boolean = false,
        handedOff: Boolean = false,
    ): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
            insert into applications (id, company_id, company_inn, company_name, legal_form, created_by,
                product_code, purpose, amount, term_months, status, created_at, updated_at, status_changed_at,
                product_version, product_catalog_snapshot, agent_id, submitted_at, client_handoff_at)
            values (?, ?, '000000000000', ?, 'ENTREPRENEUR', ?, ?, 'WORKING_CAPITAL', 300000, 12, ?, now(), now(), now(),
                1, jsonb_build_object(cast(? as text), 1), ?, case when ? then now() end, case when ? then now() end)
            """.trimIndent(),
            id,
            company,
            name,
            UUID.randomUUID(),
            product,
            status,
            product,
            agent,
            submitted,
            handedOff,
        )
        return id
    }

    private fun read(path: String, token: String, expected: Int = 200): JsonNode {
        val response = mvc.get(path) { header("Authorization", "Bearer $token") }.andReturn().response
        assertEquals(expected, response.status, response.contentAsString)
        return mapper.readTree(response.contentAsByteArray)
    }

    private fun token(role: String, company: UUID? = null): String =
        Jwts.builder().subject(UUID.randomUUID().toString()).claim(TokenClaims.ROLE, role)
            .apply { company?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
            .claim(TokenClaims.CAN_SIGN, company != null)
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(verifier.signingKey).compact()
}
