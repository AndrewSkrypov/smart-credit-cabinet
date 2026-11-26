package ru.creditcabinet.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.application.domain.Actor
import ru.creditcabinet.application.domain.ApplicationEvent
import ru.creditcabinet.application.domain.ApplicationJournal
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.service.ApplicationService
import ru.creditcabinet.application.service.Transitions
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.ProductCatalog
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

@SpringBootTest(
    properties = [
        "cabinet.application-status-events.enabled=true",
        "cabinet.application-status-events.worker-enabled=false",
        "cabinet.outbox.worker-enabled=false",
    ],
)
class ApplicationStatusOutboxIntegrationTest {
    @Autowired
    private lateinit var applications: ApplicationRepository

    @Autowired
    private lateinit var service: ApplicationService

    @Autowired
    private lateinit var transitions: Transitions

    @Autowired
    private lateinit var products: ProductCatalog

    @Autowired
    private lateinit var journal: ApplicationJournal

    @Autowired
    private lateinit var transactions: TransactionTemplate

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Test
    fun `client confirmation rolls back status, journal, both outboxes and the pipeline job together`() {
        val draft = applications.save(agentApplication(ApplicationStatus.PENDING_CLIENT_CONFIRMATION))
        val client = TokenClaims(draft.createdBy, Roles.CLIENT, null, null, companyId = draft.company.id)
        val originalContext = SecurityContextHolder.getContext()
        SecurityContextHolder.setContext(
            SecurityContextHolder.createEmptyContext().apply {
                authentication = UsernamePasswordAuthenticationToken(client, null, emptyList())
            },
        )
        try {
            assertFailsWith<ForcedRollback> {
                transactions.executeWithoutResult {
                    assertConfirmation(draft, service.confirmByClient(draft.id, bureauConsent = true))
                    throw ForcedRollback()
                }
            }

            val persisted = assertNotNull(applications.find(draft.id))
            assertEquals(ApplicationStatus.PENDING_CLIENT_CONFIRMATION, persisted.status)
            assertEquals(draft.version, persisted.version)
            assertEquals(emptyList(), journal.list(draft.id))
            assertEquals(emptyList(), statusEvents(draft.id))
            assertEquals(0, notifications(draft.id))
            assertEquals(0, dataCheckJobs(draft.id))

            val submitted = service.confirmByClient(draft.id, bureauConsent = true)
            assertConfirmation(draft, submitted)
            assertEquals(submitted.version, applications.find(draft.id)?.version)
            assertEquals(ApplicationStatus.SUBMITTED, applications.find(draft.id)?.status)
        } finally {
            SecurityContextHolder.setContext(originalContext)
            transactions.executeWithoutResult {
                jdbc.update("delete from application_status_outbox where payload ->> 'applicationId' = ?", draft.id.toString())
                jdbc.update("delete from delivery_outbox where payload ->> 'link' = ?", "/agent/applications/${draft.id}")
                jdbc.update("delete from pipeline_jobs where application_id = ?", draft.id)
            }
        }
    }

    @Test
    fun `same status workflow entry records the journal and leaves a version gap in the event stream`() {
        assertFailsWith<ForcedRollback> {
            transactions.executeWithoutResult {
                val saved = applications.save(agentApplication(ApplicationStatus.ENRICHMENT))
                val transition = saved.move(ApplicationEvent.SOURCE_FAILED, Actor.SYSTEM, saved.createdAt.plusSeconds(1))
                val updated = transitions.commit(transition)
                assertEquals(ApplicationStatus.ENRICHMENT, updated.status)
                assertEquals(assertNotNull(saved.version) + 1, updated.version)
                assertEquals(transition.entry.id, journal.list(saved.id).single().id)
                assertEquals(emptyList(), statusEvents(saved.id))
                throw ForcedRollback()
            }
        }
    }

    private fun assertConfirmation(draft: LoanApplication, submitted: LoanApplication) {
        val version = assertNotNull(draft.version) + 1
        val entry = journal.list(draft.id).single()
        val event = statusEvents(draft.id).single()
        assertEquals(version, submitted.version)
        assertEquals(ApplicationEvent.CONFIRM_AND_SUBMIT.name, entry.event)
        assertEquals(setOf("eventId", "applicationId", "applicationVersion", "status", "occurredAt"), event.fieldNames().asSequence().toSet())
        assertEquals(entry.id.toString(), event["eventId"].textValue())
        assertEquals(draft.id.toString(), event["applicationId"].textValue())
        assertEquals(version, event["applicationVersion"].longValue())
        assertEquals(ApplicationStatus.SUBMITTED.name, event["status"].textValue())
        assertEquals(submitted.statusChangedAt, Instant.parse(event["occurredAt"].textValue()))
        assertEquals(1, notifications(draft.id))
        assertEquals(1, dataCheckJobs(draft.id))
    }

    private fun agentApplication(status: ApplicationStatus): LoanApplication {
        val product = assertNotNull(products.latest("WORKING_CAPITAL"))
        return LoanApplication(
            company = CompanySnapshot(UUID.randomUUID(), "000100000004", "Синтетическая компания", LegalForm.ENTREPRENEUR),
            createdBy = UUID.randomUUID(),
            terms = ApplicationTerms(
                productCode = product.code,
                purpose = FinancingPurpose.WORKING_CAPITAL,
                amount = 1_500_000,
                termMonths = 12,
                declaredRevenue12m = 24_000_000,
            ),
            status = status,
            createdAt = Instant.now(),
            productVersion = product.version,
            agentId = UUID.randomUUID(),
        )
    }

    private fun statusEvents(id: UUID): List<JsonNode> =
        jdbc.query(
            "select payload::text from application_status_outbox where payload ->> 'applicationId' = ?",
            { rs, _ -> mapper.readTree(rs.getString(1)) },
            id.toString(),
        )

    private fun notifications(id: UUID): Int? =
        jdbc.queryForObject("select count(*) from delivery_outbox where payload ->> 'link' = ?", Int::class.java, "/agent/applications/$id")

    private fun dataCheckJobs(id: UUID): Int? = jdbc.queryForObject(
        "select count(*) from pipeline_jobs where application_id = ? and step = 'DATA_CHECK' and status = 'PENDING'",
        Int::class.java,
        id,
    )

    private class ForcedRollback : RuntimeException()
}
