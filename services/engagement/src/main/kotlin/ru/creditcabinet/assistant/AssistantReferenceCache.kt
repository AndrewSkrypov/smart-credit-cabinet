package ru.creditcabinet.assistant

import org.springframework.stereotype.Component
import ru.creditcabinet.common.limits.ValkeyState

/** A few public, context-free reference questions shared by all replicas. */
@Component
class AssistantReferenceCache(private val state: ValkeyState) {
    fun key(question: String, articles: List<Article>, model: String?): String? {
        if (!state.enabled || model.isNullOrBlank()) return null
        val first = articles.firstOrNull() ?: return null
        val normalized = question.trim().trimEnd('?', '.').lowercase().replace('ё', 'е')
        val title = first.title.lowercase().replace('ё', 'е')
        if (normalized !in setOf(title, "что такое $title", "объясни $title")) return null
        val reference = articles.joinToString("\n") { "${it.id}:${it.text}" }
        return state.key("assistant-reference", "$CACHE_VERSION\n$model\n$normalized\n$reference")
    }

    fun read(key: String): String? = runCatching { state.read(key) }.getOrNull()

    fun save(key: String, text: String) {
        if (text.length in 1..2_000) runCatching { state.eval(SET_WITH_TTL, listOf(key), listOf(text, TTL_SECONDS)) }
    }

    private companion object {
        const val CACHE_VERSION = "4"
        const val TTL_SECONDS = "1800"
        const val SET_WITH_TTL = "return redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])"
    }
}
