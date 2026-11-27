package ru.creditcabinet.assessmentservice

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.assessment.contract.v1.AssessmentCompletedV1
import ru.creditcabinet.assessment.contract.v1.AssessmentOutcomeV1
import ru.creditcabinet.assessment.contract.v1.AssessmentRequestedV1
import ru.creditcabinet.assessment.domain.SourceKind
import ru.creditcabinet.assessment.messaging.AssessmentJson
import ru.creditcabinet.assessment.messaging.AssessmentOutbox
import ru.creditcabinet.assessment.messaging.InvalidAssessmentEnvelope
import ru.creditcabinet.assessmentservice.application.ExecutionProgress
import ru.creditcabinet.assessmentservice.infrastructure.AssessmentExecutions
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
class AssessmentExecutionsTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var namedJdbc: NamedParameterJdbcTemplate

    @Autowired private lateinit var tx: TransactionTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Test
    fun `inbox replay is harmless and expired claims cannot publish after takeover`() {
        val wire = AssessmentJson(mapper)
        val start = Instant.now().plusSeconds(2)
        val clock = MutableClock(start)
        val outbox = AssessmentOutbox(namedJdbc, tx)
        val request = request(wire, start)
        val store = AssessmentExecutions(namedJdbc, tx, wire, outbox, clock, request.cycle.generation)
        val payload = wire.write(request)
        store.accept(request, payload)
        store.accept(request, payload)
        val changed = request.copy(inn = "0000000009")
        assertFailsWith<InvalidAssessmentEnvelope> { store.accept(changed, wire.write(changed)) }

        val old = assertNotNull(store.claim())
        clock.now = start.plusSeconds(46)
        val partial = ExecutionProgress(old.collection, null)
        assertFalse(store.finish(old, partial))
        val current = assertNotNull(store.claim())
        assertEquals(2, current.attempts)
        assertFalse(store.finish(old, partial))
        assertTrue(store.finish(current, partial))
        assertEquals(0, count("assessment_exchange_outbox", request))
    }

    @Test
    fun `terminal result and outbox roll back together when publication record cannot be inserted`() {
        val wire = AssessmentJson(mapper)
        val start = Instant.now().plusSeconds(2)
        val clock = MutableClock(start)
        val outbox = AssessmentOutbox(namedJdbc, tx)
        val request = request(wire, start)
        val cycle = request.cycle
        val store = AssessmentExecutions(namedJdbc, tx, wire, outbox, clock, cycle.generation)
        store.accept(request, wire.write(request))
        val claim = assertNotNull(store.claim())
        val result = AssessmentCompletedV1(
            cycle, claim.collection.receipts, claim.collection.facts, AssessmentOutcomeV1.INCOMPLETE,
            SourceKind.entries.toSet() - claim.collection.receipts.keys, start, null, null, null,
        )
        result.validateAgainst(request)
        val progress = ExecutionProgress(claim.collection, result)
        tx.executeWithoutResult { outbox.enqueue(cycle.generation, cycle.cycleId, cycle.applicationId, "{}") }
        assertFailsWith<IllegalStateException> { store.finish(claim, progress) }
        assertEquals(
            true,
            jdbc.queryForObject("select result is null from assessment_execution where cycle_id=?", Boolean::class.java, cycle.cycleId),
        )
        assertEquals(1, count("assessment_exchange_outbox", request))
        jdbc.update("delete from assessment_exchange_outbox where cycle_id=?", cycle.cycleId)
        assertTrue(store.finish(claim, progress))
        assertFalse(store.finish(claim, progress))
        assertEquals(1, count("assessment_exchange_outbox", request))
    }

    private fun request(wire: AssessmentJson, start: Instant): AssessmentRequestedV1 {
        val path = Path.of("../../contracts/events/credit/assessment/fixtures/assessment-requested.json")
        val fixture = wire.read(Files.readString(path), AssessmentRequestedV1::class.java)
        val id = UUID.randomUUID()
        return fixture.copy(
            cycle = fixture.cycle.copy(cycleId = id, generation = id.toString()),
            bureauConsent = null,
            bankPrefetch = fixture.bankPrefetch?.copy(receivedAt = start.minusSeconds(2)),
            startsAt = start.minusSeconds(1),
            deadline = start.plusSeconds(120),
        )
    }

    private fun count(table: String, request: AssessmentRequestedV1): Int = checkNotNull(
        jdbc.queryForObject("select count(*) from $table where cycle_id=?", Int::class.java, request.cycle.cycleId),
    )

    private class MutableClock(var now: Instant) : Clock() {
        override fun instant() = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId) = this
    }
}
