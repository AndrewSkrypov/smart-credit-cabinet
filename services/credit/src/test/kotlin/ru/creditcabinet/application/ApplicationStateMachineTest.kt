package ru.creditcabinet.application

import ru.creditcabinet.application.domain.Actor
import ru.creditcabinet.application.domain.ActorType
import ru.creditcabinet.application.domain.ApplicationEvent
import ru.creditcabinet.application.domain.ApplicationEvent.ACCEPT_OFFER
import ru.creditcabinet.application.domain.ApplicationEvent.APPROVE
import ru.creditcabinet.application.domain.ApplicationEvent.AUTO_OFFER
import ru.creditcabinet.application.domain.ApplicationEvent.AUTO_REJECT
import ru.creditcabinet.application.domain.ApplicationEvent.COUNTER_OFFER
import ru.creditcabinet.application.domain.ApplicationEvent.DATA_COMPLETE
import ru.creditcabinet.application.domain.ApplicationEvent.DATA_INCOMPLETE
import ru.creditcabinet.application.domain.ApplicationEvent.DECLINE_OFFER
import ru.creditcabinet.application.domain.ApplicationEvent.DISBURSED
import ru.creditcabinet.application.domain.ApplicationEvent.DISBURSEMENT_FAILED
import ru.creditcabinet.application.domain.ApplicationEvent.ENRICHMENT_DONE
import ru.creditcabinet.application.domain.ApplicationEvent.INFO_EXPIRED
import ru.creditcabinet.application.domain.ApplicationEvent.INFO_PROVIDED
import ru.creditcabinet.application.domain.ApplicationEvent.OFFER_EXPIRED
import ru.creditcabinet.application.domain.ApplicationEvent.REJECT
import ru.creditcabinet.application.domain.ApplicationEvent.REQUEST_INFO
import ru.creditcabinet.application.domain.ApplicationEvent.SIGN
import ru.creditcabinet.application.domain.ApplicationEvent.SOURCE_FAILED
import ru.creditcabinet.application.domain.ApplicationEvent.SOURCE_TIMED_OUT
import ru.creditcabinet.application.domain.ApplicationEvent.SUBMIT
import ru.creditcabinet.application.domain.ApplicationEvent.TO_MANUAL_REVIEW
import ru.creditcabinet.application.domain.ApplicationEvent.WITHDRAW
import ru.creditcabinet.application.domain.ApplicationStateMachine
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.ApplicationStatus.CLOSED
import ru.creditcabinet.application.domain.ApplicationStatus.DISBURSING
import ru.creditcabinet.application.domain.ApplicationStatus.DRAFT
import ru.creditcabinet.application.domain.ApplicationStatus.ENRICHMENT
import ru.creditcabinet.application.domain.ApplicationStatus.INFO_REQUESTED
import ru.creditcabinet.application.domain.ApplicationStatus.MANUAL_REVIEW
import ru.creditcabinet.application.domain.ApplicationStatus.OFFER_ISSUED
import ru.creditcabinet.application.domain.ApplicationStatus.REJECTED
import ru.creditcabinet.application.domain.ApplicationStatus.SCORING
import ru.creditcabinet.application.domain.ApplicationStatus.SIGNING
import ru.creditcabinet.application.domain.ApplicationStatus.SUBMITTED
import ru.creditcabinet.application.domain.ApplicationStatus.WITHDRAWN
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.CloseReason
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.product.domain.FinancingPurpose
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import ru.creditcabinet.application.domain.ApplicationStatus.DISBURSED as DISBURSED_STATUS

class ApplicationStateMachineTest {
    /** T1–T21 of docs/state-machine.md, row by row. A change of either side must change the other. */
    private val documented = setOf(
        Triple(DRAFT, SUBMIT, SUBMITTED), // T2
        Triple(SUBMITTED, DATA_COMPLETE, ENRICHMENT), // T3
        Triple(SUBMITTED, DATA_INCOMPLETE, INFO_REQUESTED), // T4
        Triple(INFO_REQUESTED, INFO_PROVIDED, SUBMITTED), // T5
        Triple(INFO_REQUESTED, INFO_EXPIRED, CLOSED), // T6
        Triple(ENRICHMENT, SOURCE_FAILED, ENRICHMENT), // T7
        Triple(ENRICHMENT, ENRICHMENT_DONE, SCORING), // T8
        Triple(ENRICHMENT, SOURCE_TIMED_OUT, MANUAL_REVIEW), // T9
        Triple(SCORING, AUTO_OFFER, OFFER_ISSUED), // T10
        Triple(SCORING, TO_MANUAL_REVIEW, MANUAL_REVIEW), // T11
        Triple(SCORING, AUTO_REJECT, REJECTED), // T12
        Triple(MANUAL_REVIEW, APPROVE, OFFER_ISSUED), // T13
        Triple(MANUAL_REVIEW, COUNTER_OFFER, OFFER_ISSUED), // T13
        Triple(MANUAL_REVIEW, REQUEST_INFO, INFO_REQUESTED), // T14
        Triple(MANUAL_REVIEW, REJECT, REJECTED), // T15
        Triple(OFFER_ISSUED, ACCEPT_OFFER, SIGNING), // T16
        Triple(OFFER_ISSUED, DECLINE_OFFER, CLOSED), // T17
        Triple(OFFER_ISSUED, OFFER_EXPIRED, CLOSED), // T17
        Triple(SIGNING, SIGN, DISBURSING), // T18
        Triple(DISBURSING, DISBURSEMENT_FAILED, DISBURSING), // T19
        Triple(DISBURSING, DISBURSED, DISBURSED_STATUS), // T20
    ) + setOf(DRAFT, SUBMITTED, INFO_REQUESTED, ENRICHMENT, MANUAL_REVIEW, OFFER_ISSUED, SIGNING).map { Triple(it, WITHDRAW, WITHDRAWN) } // T21

    private val now = Instant.parse("2026-10-21T10:00:00Z")
    private val client = Actor(ActorType.CLIENT, UUID.randomUUID())

    private fun application(status: ApplicationStatus) =
        LoanApplication(
            company = CompanySnapshot(UUID.randomUUID(), "000100000004", null, LegalForm.ENTREPRENEUR),
            createdBy = client.id!!,
            terms = ApplicationTerms("WORKING_CAPITAL", FinancingPurpose.WORKING_CAPITAL, amount = 1_000_000, termMonths = 12),
            status = status,
            createdAt = now,
        )

    private fun actorFor(event: ApplicationEvent) = Actor(event.actors.first(), UUID.randomUUID())

    @Test
    fun `the code allows exactly the documented transitions`() {
        assertEquals(documented, ApplicationStateMachine.transitions())
    }

    @Test
    fun `every other status and event pair is refused with 409 and nothing changes`() {
        for (status in ApplicationStatus.entries) {
            for (event in ApplicationEvent.entries) {
                if (documented.any { it.first == status && it.second == event }) continue
                val before = application(status)
                assertFailsWith<ConflictException>("$status + $event") { before.move(event, actorFor(event), now) }
            }
        }
    }

    @Test
    fun `an event caused by the wrong actor is refused even where the transition exists`() {
        assertFailsWith<ConflictException> { application(SUBMITTED).move(DATA_COMPLETE, client, now) }
        assertFailsWith<ConflictException> { application(MANUAL_REVIEW).move(APPROVE, Actor(ActorType.MANAGER, UUID.randomUUID()), now) }
        assertFailsWith<ConflictException> { application(DRAFT).move(SUBMIT, Actor.SYSTEM, now) }
    }

    @Test
    fun `a transition writes a journal entry with from, to and actor`() {
        val (moved, entry) = application(OFFER_ISSUED).move(DECLINE_OFFER, client, now, comment = "Нашли другой источник")
        assertEquals(CLOSED, moved.status)
        assertEquals(CloseReason.OFFER_DECLINED, moved.closeReason)
        assertEquals(now, moved.statusChangedAt)
        assertEquals(OFFER_ISSUED, entry.fromStatus)
        assertEquals(CLOSED, entry.toStatus)
        assertEquals(client, entry.actor)
        assertEquals("Нашли другой источник", entry.comment)
    }

    @Test
    fun `a retry within a status keeps the status time and is still journalled`() {
        val before = application(ENRICHMENT).copy(statusChangedAt = now.minusSeconds(60))
        val (moved, entry) = before.move(SOURCE_FAILED, Actor.SYSTEM, now, reasonCode = "BUREAU_TIMEOUT")
        assertEquals(before.statusChangedAt, moved.statusChangedAt)
        assertEquals(ENRICHMENT, entry.fromStatus)
        assertEquals(ENRICHMENT, entry.toStatus)
    }

    @Test
    fun `a running system step cannot be withdrawn, a finished application cannot change at all`() {
        assertTrue(assertFailsWith<ConflictException> { application(SCORING).move(WITHDRAW, client, now) }.message!!.contains("автоматический шаг"))
        for (terminal in ApplicationStatus.entries.filter { it.terminal }) {
            assertTrue(ApplicationStateMachine.eventsFrom(terminal, ActorType.CLIENT).isEmpty(), "$terminal")
            assertTrue(ApplicationStateMachine.eventsFrom(terminal, ActorType.SYSTEM).isEmpty(), "$terminal")
        }
    }

    @Test
    fun `the buttons of each role follow the table`() {
        assertEquals(listOf(SUBMIT, WITHDRAW), ApplicationStateMachine.eventsFrom(DRAFT, ActorType.CLIENT))
        assertEquals(listOf(APPROVE, COUNTER_OFFER, REQUEST_INFO, REJECT), ApplicationStateMachine.eventsFrom(MANUAL_REVIEW, ActorType.UNDERWRITER))
        assertEquals(listOf(REQUEST_INFO), ApplicationStateMachine.eventsFrom(MANUAL_REVIEW, ActorType.MANAGER))
        assertEquals(listOf(ACCEPT_OFFER, DECLINE_OFFER, WITHDRAW), ApplicationStateMachine.eventsFrom(OFFER_ISSUED, ActorType.CLIENT))
    }
}
