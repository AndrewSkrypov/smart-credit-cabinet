package ru.creditcabinet.analytics.infrastructure

import io.micrometer.core.instrument.MeterRegistry
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.dao.DataAccessException
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.analytics.application.CurrentStatuses
import java.security.MessageDigest

@Hidden
@RestController
@ConditionalOnProperty(name = ["ANALYTICS_STATUS_READ_ENDPOINT_ENABLED"], havingValue = "true")
class InternalCurrentStatusesController(
    private val readiness: StatusProjectionReadiness,
    private val statuses: CurrentStatuses,
    private val metrics: MeterRegistry,
    @Value("\${cabinet.analytics.kafka.topic:}") private val generation: String,
    @Value("\${ANALYTICS_STATUS_READ_TOKEN:}") token: String,
) {
    private val serviceToken = token.toByteArray(Charsets.UTF_8)

    init {
        require(token.length >= 32) { "Analytics status reads require a dedicated service token" }
        require(generation.isNotBlank()) { "Analytics status reads require an explicit stream generation" }
    }

    data class CurrentStatusesResponse(val generation: String, val currentStatuses: Map<String, Int>)

    @GetMapping("/internal/v1/analytics/current-statuses")
    fun currentStatuses(
        @RequestHeader(name = "X-Analytics-Read-Token", required = false) token: String?,
    ): ResponseEntity<CurrentStatusesResponse> {
        if (token == null || !MessageDigest.isEqual(serviceToken, token.toByteArray(Charsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).cacheControl(CacheControl.noStore()).build()
        }
        val progress = readiness.check()
        if (progress != Readiness.READY) return unavailable(progress)

        val result = try {
            statuses.currentStatuses()
        } catch (_: DataAccessException) {
            return unavailable(Readiness.PROJECTION_UNAVAILABLE)
        }
        observed(Readiness.READY)
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(CurrentStatusesResponse(generation, result))
    }

    private fun unavailable(reason: Readiness): ResponseEntity<CurrentStatusesResponse> {
        observed(reason)
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .cacheControl(CacheControl.noStore())
            .header("X-Analytics-Readiness", reason.code)
            .build()
    }

    private fun observed(readiness: Readiness) {
        metrics.counter("cabinet.analytics.projection.readiness", "state", readiness.code).increment()
    }
}
