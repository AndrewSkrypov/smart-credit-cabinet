package ru.creditcabinet.assistant.infrastructure

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import ru.creditcabinet.assistant.domain.ChatMessage
import ru.creditcabinet.assistant.domain.LanguageModel
import java.time.Clock
import java.time.Duration
import java.time.Instant

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
    @Value("\${cabinet.assistant.keep-alive:15m}") private val keepAlive: String,
    @Value("\${cabinet.assistant.warmup:false}") private val warmupEnabled: Boolean,
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
    private var warmupThread: Thread? = null

    private data class Tag(val name: String)

    private data class Tags(val models: List<Tag> = emptyList())

    private data class Reply(val message: ChatMessage?)

    override fun available(): Boolean {
        if (baseUrl == null) return false
        known?.let { (value, at) -> if (Duration.between(at, clock.instant()) < CHECK_EVERY) return value }
        val value = modelListed()
        known = value to clock.instant()
        return value
    }

    private fun modelListed(): Boolean = runCatching {
        probe.get().uri("/api/tags").retrieve().body(Tags::class.java)?.models.orEmpty().any { it.name == name || it.name.startsWith("$name:") }
    }.getOrDefault(false)

    @EventListener(ApplicationReadyEvent::class)
    fun startWarmup() {
        if (!warmupEnabled || baseUrl == null) return
        // The profile downloads weights separately. Bound the background wait without delaying readiness or notifications.
        warmupThread = Thread.ofVirtual().name("assistant-model-warmup").start {
            repeat(WARMUP_ATTEMPTS) {
                if (warmupOnce()) return@start
                try {
                    Thread.sleep(WARMUP_INTERVAL)
                } catch (_: InterruptedException) {
                    return@start
                }
            }
        }
    }

    internal fun warmupOnce(): Boolean {
        if (baseUrl == null || !modelListed()) return false
        return runCatching {
            // An empty chat loads the selected weights without submitting a question to the model.
            request(emptyList()).toBodilessEntity()
            known = true to clock.instant()
            true
        }.getOrDefault(false)
    }

    @PreDestroy
    fun stopWarmup() {
        warmupThread?.interrupt()
    }

    override fun chat(messages: List<ChatMessage>): String? {
        if (!available()) return null
        return try {
            request(messages).body(Reply::class.java)?.message?.content?.trim()?.takeIf { it.isNotEmpty() }
        } catch (exception: Exception) {
            log.warn("Assistant model did not answer, falling back to the rules: {}", exception.javaClass.simpleName)
            known = false to clock.instant()
            null
        }
    }

    private fun request(messages: List<ChatMessage>): RestClient.ResponseSpec =
        chatClient.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
            .body(
                mapOf(
                    "model" to name,
                    "messages" to messages,
                    "stream" to false,
                    "keep_alive" to keepAlive,
                    // The answer retells facts without spending its token budget on a reasoning trace.
                    "think" to false,
                    "options" to mapOf("temperature" to 0.2, "num_predict" to 300),
                ),
            ).retrieve()

    private companion object {
        val CHECK_EVERY: Duration = Duration.ofSeconds(30)
        val WARMUP_INTERVAL: Duration = Duration.ofSeconds(5)
        const val WARMUP_ATTEMPTS = 36
    }
}
