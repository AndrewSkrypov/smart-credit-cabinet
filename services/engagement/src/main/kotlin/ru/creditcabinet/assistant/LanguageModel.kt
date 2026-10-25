package ru.creditcabinet.assistant

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class ChatMessage(val role: String, val content: String)

/** A language model the assistant may use; null answers mean "not available now, use the rules". */
interface LanguageModel {
    val name: String
    fun available(): Boolean
    fun chat(messages: List<ChatMessage>): String?
}

/**
 * A model with open weights served by Ollama (the "ai" profile of docker compose). Whether it is
 * there is checked with a short timeout and remembered for half a minute, so a missing model
 * costs nothing per question.
 */
@Component
class OllamaModel(
    @Value("\${cabinet.assistant.ollama-url:}") url: String,
    @Value("\${cabinet.assistant.model:qwen2.5:3b}") override val name: String,
    @Value("\${cabinet.assistant.answer-timeout:PT30S}") answerTimeout: Duration,
    private val builder: RestClient.Builder,
    private val clock: Clock,
) : LanguageModel {
    private val log = LoggerFactory.getLogger(javaClass)
    private val baseUrl = url.takeIf { it.isNotBlank() }

    // A quick probe for "is the model there", a patient client for answers on a laptop CPU.
    private val probe by lazy { client(builder, Duration.ofSeconds(2)) }
    private val chatClient by lazy { client(builder, answerTimeout) }

    private fun client(builder: RestClient.Builder, read: Duration) =
        builder.clone().baseUrl(baseUrl!!).requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(1))
                setReadTimeout(read)
            },
        ).build()

    @Volatile private var known: Pair<Boolean, Instant>? = null

    private data class Tag(val name: String)

    private data class Tags(val models: List<Tag> = emptyList())

    private data class Reply(val message: ChatMessage?)

    override fun available(): Boolean {
        if (baseUrl == null) return false
        known?.let { (value, at) -> if (Duration.between(at, clock.instant()) < CHECK_EVERY) return value }
        val value = runCatching {
            probe.get().uri("/api/tags").retrieve().body(Tags::class.java)?.models.orEmpty().any { it.name == name || it.name.startsWith("$name:") }
        }.getOrDefault(false)
        known = value to clock.instant()
        return value
    }

    override fun chat(messages: List<ChatMessage>): String? {
        if (!available()) return null
        return try {
            chatClient.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .body(
                    mapOf(
                        "model" to name,
                        "messages" to messages,
                        "stream" to false,
                        // Native Ollama API: supported thinking models must return the final answer
                        // within the same bounded request, without spending its budget on a trace.
                        "think" to false,
                        // Low temperature: the answer retells facts, it does not invent them.
                        "options" to mapOf("temperature" to 0.2, "num_predict" to 300),
                    ),
                )
                .retrieve().body(Reply::class.java)?.message?.content?.trim()?.takeIf { it.isNotEmpty() }
        } catch (exception: Exception) {
            log.warn("Assistant model did not answer, falling back to the rules: {}", exception.javaClass.simpleName)
            known = false to clock.instant()
            null
        }
    }

    private companion object {
        val CHECK_EVERY: Duration = Duration.ofSeconds(30)
    }
}
