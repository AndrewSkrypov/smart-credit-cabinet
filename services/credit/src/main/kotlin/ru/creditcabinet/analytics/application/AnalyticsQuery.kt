package ru.creditcabinet.analytics.application

import org.springframework.stereotype.Service
import ru.creditcabinet.common.security.CurrentCaller
import java.time.Clock
import java.time.Duration
import java.time.Instant

data class FunnelStep(val code: String, val title: String, val applications: Int)

data class StageTime(val status: String, val title: String, val transitions: Int, val averageMinutes: Double?, val medianMinutes: Double?)

data class OutcomeCount(val outcome: String, val decisions: Int)

data class Analytics(
    val from: Instant,
    val to: Instant,
    /** Applications sent in the period and how far they got. */
    val funnel: List<FunnelStep>,
    /** How long applications stay in each status before moving on (finished stays only). */
    val stageTimes: List<StageTime>,
    val automaticOutcomes: List<OutcomeCount>,
    val manualOutcomes: List<OutcomeCount>,
    /** Manual decisions that went against the rules' recommendation. */
    val manualOverrides: Int,
    val disbursedAmount: Long,
    /** Applications in each status right now, drafts excluded. */
    val currentStatuses: Map<String, Int>,
)

interface AnalyticsQueries {
    fun read(from: Instant, to: Instant, projectedStatuses: Map<String, Int>?): Analytics
}

fun interface CurrentStatusesProjection {
    fun readWhenReady(): Map<String, Int>?
}

@Service
class AnalyticsQuery(
    private val queries: AnalyticsQueries,
    private val clock: Clock,
    private val projection: CurrentStatusesProjection,
) {
    fun funnel(from: Instant?, to: Instant?): Analytics {
        CurrentCaller.requireBankStaff()
        val end = to ?: clock.instant()
        val start = from ?: end.minus(Duration.ofDays(30))
        require(start.isBefore(end)) { "Начало периода должно быть раньше конца" }
        return queries.read(start, end, projection.readWhenReady())
    }
}
