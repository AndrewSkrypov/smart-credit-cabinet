package ru.creditcabinet.assistant

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.limits.ValkeyState
import ru.creditcabinet.common.outbox.DeliveryHandler
import ru.creditcabinet.common.outbox.DurableOutbox
import java.util.UUID

data class ManualReviewEvent(val schemaVersion: Int, val eventId: UUID, val applicationId: UUID)

data class ManualReviewAdvice(val revision: String, val facts: JsonNode)

data class UnderwritingSummarySource(val advice: JsonNode?, val answer: Answer? = null)

@Hidden
@RestController
class ManualReviewEventController(private val outbox: DurableOutbox, private val mapper: ObjectMapper) {
    @Transactional
    @PostMapping("/internal/v1/assistant/manual-review-events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun accept(@RequestBody event: ManualReviewEvent) {
        if (event.schemaVersion != 1) throw ApiException(HttpStatus.BAD_REQUEST, "Версия события не поддерживается")
        outbox.enqueue(ManualReviewSummaries.CHANNEL, event.eventId.toString(), mapper.writeValueAsString(event))
    }
}

@Component
class ManualReviewSummaries(
    private val applications: CreditApplications,
    private val cache: ManualReviewSummaryCache,
    private val mapper: ObjectMapper,
) : DeliveryHandler {
    override val channel = CHANNEL

    override fun deliver(payload: String) {
        if (!cache.enabled) return
        val event = mapper.readValue(payload, ManualReviewEvent::class.java)
        val priorEvent = cache.eventFor(event.applicationId)
        val advice = applications.manualReviewAdvice(event.applicationId, event.eventId) ?: return
        val text = UnderwritingSummaryInput.from(advice.facts).rulesAnswer().text
        check(cache.publish(event.applicationId, priorEvent, event.eventId, advice.revision, text)) {
            "Manual review cache changed during delivery"
        }
    }

    fun lookup(applicationId: UUID, authorization: String): UnderwritingSummarySource {
        val original = UnderwritingSummarySource(applications.advice(applicationId, authorization))
        if (original.advice == null || !cache.enabled) return original
        val eventId = runCatching { cache.eventFor(applicationId) }.getOrNull() ?: return original
        val current = runCatching { applications.manualReviewAdvice(applicationId, eventId, foreground = true) }.getOrElse { return original }

        // A confirmed change disqualifies the earlier employee snapshot, including as a fallback.
        val authorized = if (current?.facts == original.advice) original.advice else applications.advice(applicationId, authorization)
        if (current == null || authorized != current.facts) return UnderwritingSummarySource(authorized)

        val input = UnderwritingSummaryInput.from(authorized)
        val cached = runCatching { cache.text(applicationId, current.revision) }.getOrNull()
        if (cached != null) return UnderwritingSummarySource(authorized, Answer(cached, AnswerMode.RULES, input.sources))

        val answer = input.rulesAnswer()
        runCatching { cache.publish(applicationId, eventId, eventId, current.revision, answer.text) }
        return UnderwritingSummarySource(authorized, answer)
    }

    companion object {
        const val CHANNEL = "assistant-manual-review"
    }
}

/** Only rules-based text is stored here. The employee still reads the current advice from Credit. */
@Component
class ManualReviewSummaryCache(private val state: ValkeyState) {
    val enabled: Boolean get() = state.enabled

    fun eventFor(applicationId: UUID): UUID? = state.read(eventKey(applicationId))?.let(UUID::fromString)

    fun text(applicationId: UUID, revision: String): String? = state.read(summaryKey(applicationId, revision))

    fun publish(applicationId: UUID, priorEvent: UUID?, eventId: UUID, revision: String, text: String): Boolean =
        state.eval(
            PUBLISH,
            listOf(eventKey(applicationId), summaryKey(applicationId, revision)),
            listOf(priorEvent?.toString().orEmpty(), eventId.toString(), text, "86400"),
        ) == 1L

    private fun eventKey(applicationId: UUID): String = state.key("assistant-review-event", applicationId.toString())

    private fun summaryKey(applicationId: UUID, revision: String): String = state.key("assistant-review-summary", "1:$applicationId:$revision")

    private companion object {
        // A concurrent delivery may already have published a newer transition. The worker retries and asks Credit again.
        val PUBLISH = """
            local prior = redis.call('GET', KEYS[1]) or ''
            if prior ~= ARGV[1] then return 0 end
            redis.call('SET', KEYS[2], ARGV[3], 'EX', ARGV[4])
            redis.call('SET', KEYS[1], ARGV[2], 'EX', ARGV[4])
            return 1
        """.trimIndent()
    }
}

internal data class UnderwritingSummaryInput(val verdict: String, val items: List<String>, val modelVersion: Int) {
    val sources get() = listOf("Экспертная система, модель v$modelVersion")

    fun rulesAnswer(): Answer = Answer(
        (listOf(verdict) + items.take(3).map { "— $it" }).joinToString("\n"),
        AnswerMode.RULES,
        sources,
    )

    companion object {
        fun from(advice: JsonNode): UnderwritingSummaryInput = UnderwritingSummaryInput(
            advice["verdict"].asText(),
            advice["items"].map { "${it["title"].asText()}. ${it["text"].asText()}" },
            advice["modelVersion"].asInt(),
        )
    }
}
