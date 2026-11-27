package ru.creditcabinet.application

import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.http.HttpHeaders
import org.springframework.mock.web.MockHttpServletRequest
import ru.creditcabinet.application.application.CreditFailure
import ru.creditcabinet.application.controller.CreditBoundaryErrorHandler
import ru.creditcabinet.chat.infrastructure.ChatThrottle
import ru.creditcabinet.common.exception.GlobalExceptionHandler
import ru.creditcabinet.common.limits.SharedLimitExceeded
import ru.creditcabinet.common.limits.SharedLimits
import ru.creditcabinet.document.infrastructure.UploadThrottle
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class CreditThrottleFailureTest {
    private val shared = mock<SharedLimits>()
    private val clock = Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC)
    private val errors = CreditBoundaryErrorHandler(GlobalExceptionHandler())
    private val caller = UUID.randomUUID()

    @Test
    fun `shared chat and upload quotas preserve Retry-After at the HTTP adapter`() {
        val message = "Квота исчерпана"
        whenever(shared.enabled).thenReturn(true)
        doThrow(SharedLimitExceeded(message, 23)).whenever(shared).acquire(any(), any())
        val failures = listOf(
            assertFailsWith<CreditFailure.RateLimited> { ChatThrottle(mock(), clock, shared).acquire(caller) },
            assertFailsWith<CreditFailure.RateLimited> { UploadThrottle(shared, clock).acquire(caller) },
        )
        for (failure in failures) {
            assertEquals(23L, failure.retryAfterSeconds)
            val response = errors.applicationFailure(failure, MockHttpServletRequest("POST", "/upload"))
            assertEquals(429, response.statusCode.value())
            assertEquals("23", response.headers.getFirst(HttpHeaders.RETRY_AFTER))
            assertEquals(message, response.body?.message)
        }
    }

    @Test
    fun `local upload quota preserves 429 without Retry-After`() {
        val throttle = UploadThrottle(shared, clock)
        repeat(UploadThrottle.MAX_UPLOADS) { throttle.acquire(caller) }
        val failure = assertFailsWith<CreditFailure.RateLimited> { throttle.acquire(caller) }
        val response = errors.applicationFailure(failure, MockHttpServletRequest("POST", "/upload"))
        assertEquals(429, response.statusCode.value())
        assertNull(response.headers.getFirst(HttpHeaders.RETRY_AFTER))
        assertEquals("Слишком много загрузок подряд. Попробуйте через несколько минут.", response.body?.message)
    }
}
