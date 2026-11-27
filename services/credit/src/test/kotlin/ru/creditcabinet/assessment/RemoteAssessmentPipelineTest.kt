package ru.creditcabinet.assessment

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
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import ru.creditcabinet.application.domain.CompanyDirectory
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.assessment.application.AssessmentRouting
import ru.creditcabinet.assessment.contract.v1.AssessmentCompletedV1
import ru.creditcabinet.assessment.contract.v1.AssessmentOutcomeV1
import ru.creditcabinet.assessment.contract.v1.AssessmentRequestedV1
import ru.creditcabinet.assessment.contract.v1.NormalizedFactsV1
import ru.creditcabinet.assessment.domain.SnapshotEvaluation
import ru.creditcabinet.assessment.domain.SourceKind
import ru.creditcabinet.assessment.domain.creditAssessmentPayments
import ru.creditcabinet.assessment.infrastructure.JdbcCreditAssessmentStore
import ru.creditcabinet.assessment.messaging.AssessmentJson
import ru.creditcabinet.assessment.messaging.InvalidAssessmentEnvelope
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.pipeline.FakeSources
import ru.creditcabinet.pipeline.MutableClock
import ru.creditcabinet.pipeline.PipelineFakesConfig
import ru.creditcabinet.pipeline.PipelineWorker
import java.time.Duration
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@SpringBootTest
@AutoConfigureMockMvc
@Import(PipelineFakesConfig::class)
class RemoteAssessmentPipelineTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var clock: MutableClock

    @Autowired private lateinit var sources: FakeSources

    @Autowired private lateinit var worker: PipelineWorker

    @Autowired private lateinit var store: JdbcCreditAssessmentStore

    @MockitoBean private lateinit var routing: AssessmentRouting

    @MockitoBean private lateinit var companies: CompanyDirectory

    private val companyId = UUID.randomUUID()
    private val inn = "0000000000"

    @BeforeTest
    fun setup() {
        jdbc.update("update pipeline_jobs set status='CANCELLED' where status in ('PENDING','RUNNING')")
        whenever(routing.enabled).thenReturn(true)
        whenever(routing.stream).thenReturn("synthetic.first")
        whenever(routing.resultGrace).thenReturn(Duration.ofSeconds(30))
        whenever(companies.find(any())).thenReturn(CompanySnapshot(companyId, inn, "Тест", LegalForm.ENTREPRENEUR))
        sources.companies[inn] = FakeSources.healthy(inn, clock.now.atZone(ZoneOffset.UTC).toLocalDate())
    }

    @Test
    fun `one result applies with frozen versions and replay does not create another decision or offer`() {
        val id = submitted()
        worker.runDue()
        assertEquals("ENRICHMENT", status(id))
        val request = request(id)
        assertEquals(1, count("assessment_exchange_outbox", id))
        val result = complete(request)
        val wrong = result.copy(cycle = result.cycle.copy(ruleSetVersion = result.cycle.ruleSetVersion + 1))
        assertFailsWith<InvalidAssessmentEnvelope> { store.receive(wrong, wire.write(wrong)) }

        store.receive(result, wire.write(result))
        store.receive(result, wire.write(result))
        clock.advance(Duration.ofSeconds(3))
        worker.runDue()
        assertEquals("OFFER_ISSUED", status(id))
        assertEquals(1, count("decisions", id))
        assertEquals(1, count("offers", id))
        assertEquals(
            request.rules.version,
            jdbc.queryForObject("select rule_set_version from decisions where application_id=?::uuid", Int::class.java, id),
        )

        store.receive(result, wire.write(result))
        worker.runDue()
        assertEquals(1, count("decisions", id))
        assertEquals(1, count("offers", id))
    }

    @Test
    fun `restored generation cannot use an already received old result and times out to manual once`() {
        val id = submitted()
        worker.runDue()
        val request = request(id)
        val result = complete(request)
        store.receive(result, wire.write(result))

        whenever(routing.stream).thenReturn("synthetic.restored")
        clock.advance(Duration.ofMinutes(61))
        worker.runDue()
        assertEquals("MANUAL_REVIEW", status(id))
        assertEquals(1, count("decisions", id))
        assertEquals(0, count("offers", id))
        assertEquals(
            "SOURCE_UNAVAILABLE",
            jdbc.queryForObject("select reason_code from decisions where application_id=?::uuid", String::class.java, id),
        )
        store.receive(result, wire.write(result))
        worker.runDue()
        assertEquals(1, count("decisions", id))
    }

    private val wire get() = AssessmentJson(mapper)

    private fun submitted(): String {
        val token = Jwts.builder().subject(UUID.randomUUID().toString())
            .claim(TokenClaims.ROLE, "CLIENT")
            .claim(TokenClaims.FULL_NAME, "Тест")
            .claim(TokenClaims.COMPANY_ID, companyId.toString())
            .claim(TokenClaims.CAN_SIGN, true)
            .expiration(Date(System.currentTimeMillis() + 3_600_000L))
            .signWith(verifier.signingKey)
            .compact()
        val created = mvc.post("/api/v1/applications") {
            header("Authorization", "Bearer $token")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"productCode":"WORKING_CAPITAL","purpose":"WORKING_CAPITAL","amount":1000000,"termMonths":12,
                 "declaredRevenue12m":14400000,"declaredMonthlyDebtPayments":30000,"bureauConsent":true}
            """.trimIndent()
        }.andExpect { status { isCreated() } }.andReturn().response
        val id = mapper.readTree(created.contentAsString)["id"].asText()
        mvc.post("/api/v1/applications/$id/submit") {
            header("Authorization", "Bearer $token")
        }.andExpect { status { isOk() } }
        return id
    }

    private fun request(id: String): AssessmentRequestedV1 = wire.read(
        checkNotNull(jdbc.queryForObject("select request::text from credit_assessment_cycle where application_id=?::uuid", String::class.java, id)),
        AssessmentRequestedV1::class.java,
    )

    private fun complete(request: AssessmentRequestedV1): AssessmentCompletedV1 {
        val sources = sources.companies.getValue(inn)
        val evaluated = SnapshotEvaluation(creditAssessmentPayments).evaluate(request, sources, clock.now)
        return AssessmentCompletedV1(
            cycle = request.cycle,
            receipts = SourceKind.entries.associateWith {
                if (it == SourceKind.BANK) checkNotNull(request.bankPrefetch).receivedAt else clock.now
            },
            facts = NormalizedFactsV1(sources.bank, sources.registry, sources.tax, sources.bureau),
            outcome = AssessmentOutcomeV1.ASSESSED,
            missingSources = emptySet(),
            completedAt = clock.now,
            assessment = evaluated.assessment,
            policy = evaluated.policy,
            appliedPreApprovalId = evaluated.appliedPreApprovalId,
        )
    }

    private fun status(id: String): String? = jdbc.queryForObject("select status from applications where id=?::uuid", String::class.java, id)

    private fun count(table: String, id: String): Int? = jdbc.queryForObject("select count(*) from $table where application_id=?::uuid", Int::class.java, id)
}
