package ru.creditcabinet.assistant.infrastructure

import org.springframework.stereotype.Component
import ru.creditcabinet.common.limits.ValkeyState
import java.util.UUID

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
