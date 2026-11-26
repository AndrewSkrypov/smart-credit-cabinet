package ru.creditcabinet.analytics.infrastructure

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import ru.creditcabinet.analytics.application.CurrentStatuses
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InternalCurrentStatusesControllerTest {
    private val token = "analytics-test-service-token-over-32-characters"
    private val topic = "application-status-test-generation"

    @Test
    fun `internal endpoint checks service identity before accessing readiness or data`() {
        val controller = InternalCurrentStatusesController(
            StatusProjectionReadiness { error("Unauthenticated readiness calculation") },
            object : CurrentStatuses {
                override fun currentStatuses(): Map<String, Int> = error("Unauthenticated data read")
            },
            SimpleMeterRegistry(),
            topic,
            token,
        )
        for (credential in listOf(null, "incorrect", "a-bank-user-jwt")) {
            val response = controller.currentStatuses(credential)
            assertEquals(HttpStatus.UNAUTHORIZED, response.statusCode)
            assertNull(response.body)
        }
    }

    @Test
    fun `endpoint reports not ready until bootstrap and delivery have caught up`() {
        var readiness = Readiness.SNAPSHOT_PENDING
        var reads = 0
        val metrics = SimpleMeterRegistry()
        val controller = InternalCurrentStatusesController(
            StatusProjectionReadiness { readiness },
            object : CurrentStatuses {
                override fun currentStatuses(): Map<String, Int> = mapOf("SUBMITTED" to ++reads)
            },
            metrics,
            topic,
            token,
        )
        for (reason in listOf(Readiness.SNAPSHOT_PENDING, Readiness.OFFSET_UNAVAILABLE, Readiness.LAG, Readiness.DLQ)) {
            readiness = reason
            val response = controller.currentStatuses(token)
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.statusCode)
            assertEquals(reason.code, response.headers.getFirst("X-Analytics-Readiness"))
            assertNull(response.body)
            assertEquals(1.0, metrics.get("cabinet.analytics.projection.readiness").tag("state", reason.code).counter().count())
        }
        assertEquals(0, reads)

        readiness = Readiness.READY
        val response = controller.currentStatuses(token)
        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(InternalCurrentStatusesController.CurrentStatusesResponse(topic, mapOf("SUBMITTED" to 1)), response.body)
        assertEquals("no-store", response.headers.cacheControl)
    }
}
