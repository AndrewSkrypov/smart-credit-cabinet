package ru.creditcabinet.analytics

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import ru.creditcabinet.analytics.application.Analytics
import ru.creditcabinet.analytics.application.AnalyticsQueries
import ru.creditcabinet.analytics.application.AnalyticsQuery
import ru.creditcabinet.analytics.application.CurrentStatusesProjection
import ru.creditcabinet.analytics.application.FunnelStep
import ru.creditcabinet.application.application.CreditFailure.Forbidden
import ru.creditcabinet.application.application.CreditFailure.Unauthenticated
import ru.creditcabinet.application.infrastructure.SpringCreditCaller
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AnalyticsProjectionQueryTest {
    private val end = Instant.parse("2025-02-02T00:00:00Z")
    private val start = Instant.parse("2025-02-01T00:00:00Z")
    private val clock = Clock.fixed(end, ZoneOffset.UTC)

    @AfterEach
    fun clearCaller() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `denied requests never read the remote projection`() {
        val projection = CurrentStatusesProjection { error("Projection invoked before authorization") }
        val queries = object : AnalyticsQueries {
            override fun read(from: Instant, to: Instant, projectedStatuses: Map<String, Int>?): Analytics = error("No read for denied requests")
        }
        val query = AnalyticsQuery(queries, clock, projection, SpringCreditCaller())
        assertFailsWith<Unauthenticated> { query.funnel(start, end) }
        for (role in listOf(Roles.CLIENT, Roles.AGENT)) {
            actAs(role)
            assertFailsWith<Forbidden> { query.funnel(start, end) }
        }
    }

    @Test
    fun `bank roles preserve SQL funnel and switch only the current statuses including an empty projection`() {
        var projected: Map<String, Int>? = emptyMap()
        val sqlStatuses = mapOf("SUBMITTED" to 10)
        val steps = listOf(FunnelStep("SUBMITTED", "Отправлены", 12))
        val query = AnalyticsQuery(
            object : AnalyticsQueries {
                override fun read(from: Instant, to: Instant, projectedStatuses: Map<String, Int>?): Analytics =
                    Analytics(from, to, steps, emptyList(), emptyList(), emptyList(), 0, 42, projectedStatuses ?: sqlStatuses)
            },
            clock,
            CurrentStatusesProjection { projected },
            SpringCreditCaller(),
        )
        for (role in listOf(Roles.MANAGER, Roles.UNDERWRITER, Roles.RISK_ADMIN)) {
            actAs(role)
            val read = query.funnel(start, end)
            assertEquals(emptyMap(), read.currentStatuses)
            assertEquals(steps, read.funnel)
            assertEquals(42L, read.disbursedAmount)
            assertEquals(start, read.from)
            assertEquals(end, read.to)
        }
        projected = null
        assertEquals(sqlStatuses, query.funnel(start, end).currentStatuses)
    }

    private fun actAs(role: String) {
        val principal = TokenClaims(UUID.randomUUID(), role, null, null)
        SecurityContextHolder.getContext().authentication = UsernamePasswordAuthenticationToken(principal, null, emptyList())
    }
}
