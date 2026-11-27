package ru.creditcabinet.advice

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.application.domain.Actor
import ru.creditcabinet.application.domain.ActorType
import ru.creditcabinet.application.domain.ApplicationEvent
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.service.Transitions
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.product.domain.FinancingPurpose
import java.time.Clock
import java.time.Instant
import java.util.Date
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
class ManualReviewAdviceContractIntegrationTest {
    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var applications: ApplicationRepository

    @Autowired
    lateinit var transitions: Transitions

    @Autowired
    lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    lateinit var tx: TransactionTemplate

    @Autowired
    lateinit var mapper: ObjectMapper

    @Autowired
    lateinit var clock: Clock

    @Value("\${cabinet.auth.jwt-secret}")
    lateinit var jwtSecret: String

    private val createdApps = mutableListOf<UUID>()

    @AfterEach
    fun cleanup() {
        createdApps.forEach { id ->
            jdbc.update("delete from delivery_outbox where payload::text like :pattern", mapOf("pattern" to "%$id%"))
        }
        createdApps.clear()
    }

    @Test
    fun `transition into MANUAL_REVIEW enqueues outbox event and serves matching internal advice with revision`() {
        val appId = createApplicationInScoring()
        val firstEventId = moveToManualReview(appId)

        val outboxPayloads = jdbc.queryForList(
            "select payload::text from delivery_outbox where channel = :channel and event_key = :key",
            mapOf("channel" to "assistant-manual-review", "key" to firstEventId.toString()),
            String::class.java,
        )
        assertEquals(1, outboxPayloads.size, "Expected one outbox event for transition into MANUAL_REVIEW")
        val eventJson = mapper.readTree(outboxPayloads.single())
        assertEquals(1, eventJson["schemaVersion"].asInt())
        assertEquals(firstEventId.toString(), eventJson["eventId"].asText())
        assertEquals(appId.toString(), eventJson["applicationId"].asText())

        // Internal advice matches public advice JSON once adviceRevision is removed
        val internalResponse = mockMvc.get("/internal/v1/underwriting/applications/$appId/advice?eventId=$firstEventId")
            .andReturn().response
        assertEquals(200, internalResponse.status, internalResponse.getContentAsString(Charsets.UTF_8))
        val internalNode = mapper.readTree(internalResponse.getContentAsString(Charsets.UTF_8)) as ObjectNode
        val firstRevision = internalNode.remove("adviceRevision")?.asText()
        assertTrue(firstRevision != null && firstRevision.matches(Regex("[0-9a-f]{64}")), "Invalid adviceRevision: $firstRevision")

        val underwriterToken = Jwts.builder()
            .subject(UUID.randomUUID().toString())
            .claim(TokenClaims.ROLE, Roles.UNDERWRITER)
            .claim(TokenClaims.FULL_NAME, "Андеррайтер")
            .claim(TokenClaims.EMAIL, "uw@demo.test")
            .claim(TokenClaims.CREDENTIALS_VERSION, 0)
            .issuedAt(Date.from(Instant.now()))
            .expiration(Date.from(Instant.now().plusSeconds(3600)))
            .signWith(Keys.hmacShaKeyFor(jwtSecret.toByteArray(Charsets.UTF_8)))
            .compact()
        val publicResponse = mockMvc.get("/api/v1/underwriting/applications/$appId/advice") {
            header("Authorization", "Bearer $underwriterToken")
        }.andReturn().response
        assertEquals(200, publicResponse.status, publicResponse.getContentAsString(Charsets.UTF_8))
        val publicNode = mapper.readTree(publicResponse.getContentAsString(Charsets.UTF_8))
        assertEquals(publicNode, internalNode)

        // Random / unknown eventId -> 409 Conflict
        val wrongEventResp = mockMvc.get("/internal/v1/underwriting/applications/$appId/advice?eventId=${UUID.randomUUID()}")
            .andReturn().response
        assertEquals(409, wrongEventResp.status)

        // Re-enter MANUAL_REVIEW (MANUAL_REVIEW -> INFO_REQUESTED -> SUBMITTED -> ENRICHMENT -> SCORING -> MANUAL_REVIEW)
        val secondEventId = reenterManualReview(appId)
        assertNotEquals(firstEventId, secondEventId)

        // Old eventId is now stale -> 409 Conflict
        val staleResp = mockMvc.get("/internal/v1/underwriting/applications/$appId/advice?eventId=$firstEventId")
            .andReturn().response
        assertEquals(409, staleResp.status)

        // New eventId returns 200 with a new adviceRevision
        val secondResp = mockMvc.get("/internal/v1/underwriting/applications/$appId/advice?eventId=$secondEventId")
            .andReturn().response
        assertEquals(200, secondResp.status)
        val secondRevision = mapper.readTree(secondResp.getContentAsString(Charsets.UTF_8))["adviceRevision"].asText()
        assertNotEquals(firstRevision, secondRevision)

        // Exiting MANUAL_REVIEW (REJECT) makes latest eventId return 409 Conflict
        tx.executeWithoutResult {
            val current = applications.find(appId)!!
            transitions.commit(
                current.move(
                    ApplicationEvent.REJECT,
                    Actor(ActorType.UNDERWRITER, UUID.randomUUID()),
                    clock.instant(),
                    reasonCode = "HIGH_RISK",
                    comment = "Тестовый отказ",
                ),
            )
        }
        val exitedResp = mockMvc.get("/internal/v1/underwriting/applications/$appId/advice?eventId=$secondEventId")
            .andReturn().response
        assertEquals(409, exitedResp.status)
    }

    private fun createApplicationInScoring(): UUID {
        val now = clock.instant()
        val companyId = UUID.randomUUID()
        val app = LoanApplication(
            company = CompanySnapshot(companyId, "0077012345", "ООО Тест Сводки", LegalForm.LEGAL_ENTITY),
            createdBy = UUID.randomUUID(),
            terms = ApplicationTerms(
                productCode = "WORKING_CAPITAL",
                purpose = FinancingPurpose.WORKING_CAPITAL,
                amount = 2_000_000L,
                termMonths = 12,
                declaredRevenue12m = 20_000_000L,
                declaredMonthlyDebtPayments = 50_000L,
                bureauConsent = true,
            ),
            status = ApplicationStatus.SCORING,
            createdAt = now,
            submittedAt = now,
            productVersion = 1,
            productCatalogSnapshot = mapOf("WORKING_CAPITAL" to 1L),
        )
        val saved = tx.execute { applications.save(app) }!!
        createdApps += saved.id
        return saved.id
    }

    private fun moveToManualReview(appId: UUID): UUID =
        tx.execute {
            val current = applications.find(appId)!!
            val transition = current.move(ApplicationEvent.TO_MANUAL_REVIEW, Actor.SYSTEM, clock.instant())
            transitions.commit(transition)
            transition.entry.id
        }!!

    private fun reenterManualReview(appId: UUID): UUID =
        tx.execute {
            val uw = Actor(ActorType.UNDERWRITER, UUID.randomUUID())
            val client = Actor(ActorType.CLIENT, UUID.randomUUID())
            var current = applications.find(appId)!!
            current = transitions.commit(current.move(ApplicationEvent.REQUEST_INFO, uw, clock.instant(), comment = "Уточнение"))
            current = transitions.commit(current.move(ApplicationEvent.INFO_PROVIDED, client, clock.instant()))
            current = transitions.commit(current.move(ApplicationEvent.DATA_COMPLETE, Actor.SYSTEM, clock.instant()))
            current = transitions.commit(current.move(ApplicationEvent.ENRICHMENT_DONE, Actor.SYSTEM, clock.instant()))
            val transition = current.move(ApplicationEvent.TO_MANUAL_REVIEW, Actor.SYSTEM, clock.instant())
            transitions.commit(transition)
            transition.entry.id
        }!!
}
