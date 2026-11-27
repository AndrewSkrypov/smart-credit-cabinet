package ru.creditcabinet.assistant.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import ru.creditcabinet.assistant.domain.ManualReviewEvent
import ru.creditcabinet.assistant.infrastructure.CreditApplications
import ru.creditcabinet.assistant.infrastructure.ManualReviewSummaryCache
import ru.creditcabinet.common.outbox.DeliveryHandler
import java.util.UUID

data class UnderwritingSummarySource(val advice: JsonNode?, val answer: Answer? = null)

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
