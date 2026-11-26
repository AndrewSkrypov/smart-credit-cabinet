package ru.creditcabinet.analytics.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import ru.creditcabinet.analytics.application.CurrentStatusesProjection
import ru.creditcabinet.application.domain.ApplicationStatus
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Component
class HttpCurrentStatusesProjection(
    private val jdbc: NamedParameterJdbcTemplate,
    private val mapper: ObjectMapper,
    private val metrics: MeterRegistry,
    @Value("\${ANALYTICS_STATUS_READ_ENABLED:false}") private val enabled: Boolean,
    @Value("\${cabinet.application-status-events.enabled:false}") private val captureEnabled: Boolean,
    @Value("\${cabinet.application-status-events.worker-enabled:true}") private val workerEnabled: Boolean,
    @Value("\${cabinet.application-status-events.topic:}") private val generation: String,
    @Value("\${ANALYTICS_STATUS_READ_URL:}") url: String,
    @Value("\${ANALYTICS_STATUS_READ_TOKEN:}") token: String,
    @Value("\${ANALYTICS_STATUS_READ_TIMEOUT_MS:500}") private val timeoutMs: Long,
) : CurrentStatusesProjection {
    private val request = if (enabled && captureEnabled && workerEnabled && generation.isNotBlank() && token.length >= 32 && timeoutMs > 0) {
        createRequest(url, token)
    } else {
        null
    }

    private val client = request?.let {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(timeoutMs))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    }

    override fun readWhenReady(): Map<String, Int>? {
        if (!enabled) return sql("disabled")
        if (!captureEnabled || !workerEnabled) return sql("producer_disabled")
        val remoteRequest = request ?: return sql("unconfigured")
        val http = client ?: return sql("unconfigured")
        if (!outboxDrained()) return sql("outbox_pending")

        val future = http.sendAsync(remoteRequest, HttpResponse.BodyHandlers.ofByteArray())
        val response = try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            return sql("timeout")
        } catch (_: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            return sql("interrupted")
        } catch (exception: ExecutionException) {
            return sql(if (exception.cause is HttpTimeoutException) "timeout" else "remote_unavailable")
        }
        if (response.statusCode() != 200) {
            val statusReason = response.headers().firstValue("X-Analytics-Readiness").orElse("")
            return sql(statusReason.takeIf { it in REMOTE_REASONS } ?: "remote_http")
        }
        val counts = parse(response.body()) ?: return sql("invalid_projection")
        if (!outboxDrained()) return sql("outbox_pending")

        selected("projection", "ready")
        return counts
    }

    private fun outboxDrained(): Boolean = try {
        jdbc.queryForObject(
            """
            SELECT NOT EXISTS (
                SELECT 1 FROM application_status_outbox
                WHERE stream_generation = :generation AND state IN ('PENDING', 'RUNNING', 'FAILED')
            )
            """.trimIndent(),
            mapOf("generation" to generation),
            Boolean::class.java,
        ) == true
    } catch (_: DataAccessException) {
        false
    }

    private fun createRequest(url: String, token: String): HttpRequest? = runCatching {
        val origin = URI.create(url)
        require(origin.scheme in setOf("http", "https") && origin.host != null)
        require(origin.userInfo == null && origin.query == null && origin.fragment == null && origin.path in setOf("", "/"))
        HttpRequest.newBuilder(origin.resolve("/internal/v1/analytics/current-statuses"))
            .header("Accept", "application/json")
            .header("X-Analytics-Read-Token", token)
            .timeout(Duration.ofMillis(timeoutMs))
            .GET()
            .build()
    }.getOrNull()

    private fun parse(body: ByteArray): Map<String, Int>? {
        if (body.size > MAX_RESPONSE_BYTES) return null
        val payload = try {
            mapper.readTree(body)
        } catch (_: IOException) {
            return null
        } ?: return null
        if (!payload.isObject || payload.path("generation").textValue() != generation) return null
        val counts = payload.path("currentStatuses")
        if (!counts.isObject) return null
        return buildMap {
            for ((status, number) in counts.properties()) {
                if (status !in VISIBLE_STATUSES || !number.isIntegralNumber || !number.canConvertToInt() || number.intValue() <= 0) return null
                put(status, number.intValue())
            }
        }
    }

    private fun sql(reason: String): Map<String, Int>? {
        selected("sql", reason)
        return null
    }

    private fun selected(source: String, reason: String) {
        metrics.counter("cabinet.analytics.current.statuses.read", "source", source, "reason", reason).increment()
    }

    @PreDestroy
    fun close() {
        client?.shutdownNow()
    }

    private companion object {
        const val MAX_RESPONSE_BYTES = 16_384
        val VISIBLE_STATUSES = ApplicationStatus.entries
            .filterNot { it == ApplicationStatus.DRAFT || it == ApplicationStatus.PENDING_CLIENT_CONFIRMATION }
            .map(ApplicationStatus::name)
            .toSet()
        val REMOTE_REASONS = setOf(
            "consumer_disabled",
            "snapshot_pending",
            "broker_unavailable",
            "offset_unavailable",
            "lag",
            "dlq",
            "projection_unavailable",
        )
    }
}
