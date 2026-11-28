package ru.creditcabinet.assistant.application

import ru.creditcabinet.assistant.domain.Answer
import ru.creditcabinet.assistant.domain.AnswerMode
import java.util.UUID

data class ManualReviewEvent(val schemaVersion: Int, val eventId: UUID, val applicationId: UUID)

data class ManualReviewAdvice(val revision: String, val facts: AdviceSnapshot)

data class UnderwritingSummarySource(val advice: AdviceSnapshot?, val answer: Answer? = null)

/** The snapshot identity covers all advice fields, including those omitted from the rendered summary. */
data class AdviceSnapshot(val summary: UnderwritingSummaryInput, val identity: String)

fun interface ManualReviewEvents {
    fun enqueue(event: ManualReviewEvent)
}

interface ReviewSummaryCache {
    val enabled: Boolean
    fun eventFor(applicationId: UUID): UUID?
    fun text(applicationId: UUID, revision: String): String?
    fun publish(applicationId: UUID, priorEvent: UUID?, eventId: UUID, revision: String, text: String): Boolean
}

class ManualReviewIngress(private val events: ManualReviewEvents) {
    fun accept(event: ManualReviewEvent) {
        if (event.schemaVersion != 1) throw AssistantFailure(AssistantFailureReason.INVALID_EVENT, "Версия события не поддерживается")
        events.enqueue(event)
    }
}

class ManualReviewSummaries(
    private val applications: AssistantApplications,
    private val cache: ReviewSummaryCache,
) {
    val enabled get() = cache.enabled

    fun deliver(event: ManualReviewEvent) {
        val priorEvent = cache.eventFor(event.applicationId)
        val advice = applications.manualReviewAdvice(event.applicationId, event.eventId) ?: return
        val text = advice.facts.summary.rulesAnswer().text
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

        val input = authorized.summary
        val cached = runCatching { cache.text(applicationId, current.revision) }.getOrNull()
        if (cached != null) return UnderwritingSummarySource(authorized, Answer(cached, AnswerMode.RULES, input.sources))

        val answer = input.rulesAnswer()
        runCatching { cache.publish(applicationId, eventId, eventId, current.revision, answer.text) }
        return UnderwritingSummarySource(authorized, answer)
    }
}

data class UnderwritingSummaryInput(val verdict: String, val items: List<String>, val modelVersion: Int) {
    val sources get() = listOf("Экспертная система, модель v$modelVersion")

    fun rulesAnswer(): Answer = Answer(
        (listOf(verdict) + items.take(3).map { "— $it" }).joinToString("\n"),
        AnswerMode.RULES,
        sources,
    )
}
