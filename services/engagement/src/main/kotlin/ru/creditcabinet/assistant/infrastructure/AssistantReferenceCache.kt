package ru.creditcabinet.assistant.infrastructure

import org.springframework.stereotype.Component
import ru.creditcabinet.assistant.application.ReferenceCache
import ru.creditcabinet.common.limits.ValkeyState

/** A few public, context-free reference questions shared by all replicas. */
@Component
class AssistantReferenceCache(private val state: ValkeyState) : ReferenceCache {
    override fun key(material: String): String? =
        if (state.enabled) state.key("assistant-reference", "$CACHE_VERSION\n$material") else null

    override fun read(key: String): String? = runCatching { state.read(key) }.getOrNull()

    override fun save(key: String, text: String) {
        if (text.length in 1..2_000) runCatching { state.eval(SET_WITH_TTL, listOf(key), listOf(text, TTL_SECONDS)) }
    }

    private companion object {
        const val CACHE_VERSION = "5"
        const val TTL_SECONDS = "1800"
        const val SET_WITH_TTL = "return redis.call('SET', KEYS[1], ARGV[1], 'EX', ARGV[2])"
    }
}
