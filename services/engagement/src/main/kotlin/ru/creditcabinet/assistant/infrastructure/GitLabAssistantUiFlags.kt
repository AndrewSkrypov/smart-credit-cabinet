package ru.creditcabinet.assistant.infrastructure

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import ru.creditcabinet.assistant.application.AssistantUiFlags
import java.net.HttpURLConnection
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class GitLabAssistantUiFlags(
    @Value("\${cabinet.assistant.gitlab.api-url:}") apiUrl: String,
    @Value("\${cabinet.assistant.gitlab.instance-id:}") private val instanceId: String,
    @Value("\${cabinet.assistant.gitlab.app-name:}") private val appName: String,
    private val mapper: ObjectMapper,
    private val clock: Clock,
) : AssistantUiFlags {
    private val endpoint = runCatching {
        URI.create(apiUrl.trimEnd('/') + "/client/features").takeIf {
            val loopbackHttp = it.scheme == "http" && it.host in setOf("127.0.0.1", "localhost")
            val validScheme = it.scheme == "https" || loopbackHttp
            validScheme && it.host != null && it.userInfo == null && it.query == null && it.fragment == null
        }
    }.getOrNull()?.takeIf { instanceId.isNotBlank() && appName.isNotBlank() }

    @Volatile private var snapshot: Snapshot? = null
    private var poller: Thread? = null

    override fun isKeshaEnabled(userId: UUID): Boolean =
        snapshot?.let {
            clock.instant().isBefore(it.validUntil) && (it.allUsers || userId.toString() in it.userIds)
        } == true

    @EventListener(ApplicationReadyEvent::class)
    fun startPolling() {
        if (endpoint == null) return
        poller = Thread.ofVirtual().name("assistant-ui-flags").start {
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val started = System.nanoTime()
                    refresh()
                    val wait = POLL_INTERVAL.minusNanos(System.nanoTime() - started)
                    if (!wait.isNegative) Thread.sleep(wait)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }

    @PreDestroy
    fun stopPolling() {
        poller?.interrupt()
    }

    internal fun refresh() {
        // Keep the upstream URL, credentials and response body out of logs and telemetry.
        snapshot = runCatching { fetchFlag()?.let(::readSnapshot) }.getOrNull()
    }

    private fun fetchFlag(): JsonNode? {
        val connection = endpoint?.toURL()?.openConnection() as? HttpURLConnection ?: return null
        try {
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 2_000
            connection.readTimeout = 2_000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("UNLEASH-INSTANCEID", instanceId)
            connection.setRequestProperty("UNLEASH-APPNAME", appName)
            if (connection.responseCode != 200) return null
            return connection.inputStream.use { body ->
                val bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1)
                if (bytes.size > MAX_RESPONSE_BYTES) null else mapper.readTree(bytes)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readSnapshot(response: JsonNode): Snapshot? {
        val features = response.path("features").takeIf(JsonNode::isArray) ?: return null
        val flag = features.singleOrNull { it.path("name").asText() == FLAG_NAME } ?: return null
        if (!flag.path("enabled").booleanValue()) return null
        val strategies = flag.path("strategies").takeIf(JsonNode::isArray) ?: return null
        val supported = strategies.filter { strategy ->
            val constraints = strategy.path("constraints")
            constraints.isMissingNode || constraints.isArray && constraints.isEmpty
        }
        val allUsers = supported.any {
            val parameters = it.path("parameters")
            it.path("name").asText() == "default" && parameters.isObject && parameters.isEmpty
        }
        val userIds = supported.filter { it.path("name").asText() == "userWithId" }
            .flatMap { it.path("parameters").path("userIds").asText().split(',') }
            .map(String::trim)
            .toSet()
        return Snapshot(allUsers, userIds, clock.instant().plus(SNAPSHOT_TTL))
    }

    private data class Snapshot(val allUsers: Boolean, val userIds: Set<String>, val validUntil: Instant)

    private companion object {
        const val FLAG_NAME = "assistant_launcher_fantech"
        const val MAX_RESPONSE_BYTES = 128 * 1024
        val POLL_INTERVAL: Duration = Duration.ofSeconds(30)
        val SNAPSHOT_TTL: Duration = Duration.ofSeconds(60)
    }
}
