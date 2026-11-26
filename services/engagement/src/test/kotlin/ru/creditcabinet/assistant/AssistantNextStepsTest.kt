package ru.creditcabinet.assistant

import com.fasterxml.jackson.databind.ObjectMapper
import org.mockito.kotlin.mock
import ru.creditcabinet.assistant.application.AssistantHint
import ru.creditcabinet.assistant.application.AssistantNextSteps
import ru.creditcabinet.assistant.application.HintType
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AssistantNextStepsTest {
    private val now = Instant.parse("2026-11-25T12:00:00Z")
    private val mapper = ObjectMapper()
    private val nextSteps = AssistantNextSteps(mock(), mock(), Clock.fixed(now, ZoneOffset.UTC))
    private val applicationId = UUID.randomUUID()

    @Test
    fun `client draft review includes both fields and documents`() {
        val draft = mapper.readTree(
            """
            {"status":"DRAFT","requiredDocuments":[{"title":"Счёт","uploaded":false},{"title":"Паспорт","uploaded":true}],
              "submissionProblems":["Укажите выручку","Без согласия на запрос в бюро кредитных историй заявку не рассмотреть"]}
            """,
        )
        val client = nextSteps.summarize(applicationId, draft, agent = false, now).single()
        val agent = nextSteps.summarize(applicationId, draft, agent = true, now).single()
        assertEquals(HintType.MISSING_DOCUMENTS, client.type)
        assertTrue(listOf("Счёт", "Укажите выручку", "бюро кредитных историй").all { it in client.detail })
        assertTrue(listOf("Счёт", "Укажите выручку").all { it in agent.detail })
        assertFalse("бюро кредитных историй" in agent.detail)
        assertFalse("Паспорт" in agent.detail)
    }

    @Test
    fun `just-expired document requests do not offer a reminder`() {
        val infoRequestId = UUID.randomUUID()
        fun request(deadline: Instant) = mapper.readTree(
            """{"status":"INFO_REQUESTED","process":{"infoRequest":{"id":"$infoRequestId","deadline":"$deadline",
                "items":[{"title":"Счёт","documentType":"OTHER"}]}}}""",
        )
        val open = request(now.plusSeconds(1))
        val agentHint = nextSteps.summarize(applicationId, open, agent = true, now).single()
        assertTrue(agentHint.canRemind)
        assertEquals(infoRequestId, agentHint.infoRequestId)
        assertFalse(nextSteps.summarize(applicationId, open, agent = false, now).single().canRemind)
        assertTrue(nextSteps.summarize(applicationId, request(now), agent = true, now).isEmpty())
    }

    @Test
    fun `an offer at the warning boundary also retains its counter offer action`() {
        val due = now.plus(Duration.ofHours(48))
        val offer = mapper.readTree("""{"status":"OFFER_ISSUED","process":{"offer":{"kind":"COUNTER","validUntil":"$due"}}}""")
        assertEquals(
            listOf(HintType.OFFER_DEADLINE, HintType.ALTERNATIVE),
            nextSteps.summarize(applicationId, offer, agent = true, now).map(AssistantHint::type),
        )
    }
}
