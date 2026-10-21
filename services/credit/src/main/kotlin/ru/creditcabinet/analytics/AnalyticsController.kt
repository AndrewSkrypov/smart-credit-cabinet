package ru.creditcabinet.analytics

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.common.security.CurrentCaller
import java.sql.Timestamp
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

/**
 * The funnel of the credit pipeline, computed from the journal and the decisions: no separate
 * analytics store, so the numbers are exactly what happened to the applications.
 */
@Tag(name = "Analytics")
@RestController
@RequestMapping("/api/v1/analytics")
class AnalyticsController(private val jdbc: NamedParameterJdbcTemplate, private val clock: Clock) {
    @Operation(summary = "Воронка, время на этапах и исходы решений за период (по умолчанию 30 дней)")
    @GetMapping("/funnel")
    fun funnel(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
    ): Analytics {
        CurrentCaller.requireBankStaff()
        val end = to ?: clock.instant()
        val start = from ?: end.minus(Duration.ofDays(30))
        require(start.isBefore(end)) { "Начало периода должно быть раньше конца" }
        val period = mapOf("from" to Timestamp.from(start), "to" to Timestamp.from(end))
        return Analytics(
            from = start,
            to = end,
            funnel = funnelSteps(period),
            stageTimes = stageTimes(period),
            automaticOutcomes = outcomes("SYSTEM", period),
            manualOutcomes = outcomes("MANUAL", period),
            manualOverrides = overrides(period),
            disbursedAmount = jdbc.queryForObject(
                "select coalesce(sum(amount), 0) from loans where disbursed_at >= :from and disbursed_at < :to",
                period,
                Long::class.java,
            )!!,
            currentStatuses = jdbc.query("select status, count(*) as n from applications where status <> 'DRAFT' group by status", emptyMap<String, Any>()) {
                    rs,
                    _,
                ->
                rs.getString("status") to rs.getInt("n")
            }.toMap(),
        )
    }

    /** Applications sent in the period and how many of them reached each point by now. */
    private fun funnelSteps(period: Map<String, Any>): List<FunnelStep> {
        val sent = jdbc.queryForObject("select count(*) from applications where submitted_at >= :from and submitted_at < :to", period, Int::class.java)!!
        fun distinct(sql: String) = jdbc.queryForObject(sql, period, Int::class.java)!!
        fun reachedAny(vararg events: String) =
            distinct(
                """
                select count(distinct e.application_id) from application_events e join applications a on a.id = e.application_id
                where a.submitted_at >= :from and a.submitted_at < :to and e.event in (${events.joinToString { "'$it'" }})
                """.trimIndent(),
            )
        return listOf(
            FunnelStep("SUBMITTED", "Отправлены", sent),
            FunnelStep("DATA_COMPLETE", "Данные полные", reachedAny("DATA_COMPLETE")),
            FunnelStep("DECIDED", "Принято решение", reachedAny("AUTO_OFFER", "AUTO_REJECT", "APPROVE", "COUNTER_OFFER", "REJECT")),
            FunnelStep("OFFER_ISSUED", "Сделано предложение", reachedAny("AUTO_OFFER", "APPROVE", "COUNTER_OFFER")),
            FunnelStep("ACCEPTED", "Предложение принято", reachedAny("ACCEPT_OFFER")),
            FunnelStep("SIGNED", "Договор подписан", reachedAny("SIGN")),
            FunnelStep("DISBURSED", "Выдано", reachedAny("DISBURSED")),
        )
    }

    private fun stageTimes(period: Map<String, Any>): List<StageTime> =
        jdbc.query(
            """
            with moves as (
                select application_id, to_status, occurred_at,
                       lead(occurred_at) over (partition by application_id order by seq) as left_at
                from application_events
                where from_status is distinct from to_status
            )
            select to_status, count(*) as n,
                   avg(extract(epoch from left_at - occurred_at)) / 60 as avg_min,
                   percentile_cont(0.5) within group (order by extract(epoch from left_at - occurred_at)) / 60 as median_min
            from moves
            where left_at is not null and occurred_at >= :from and occurred_at < :to
            group by to_status
            """.trimIndent(),
            period,
        ) { rs, _ ->
            val status = rs.getString("to_status")
            StageTime(
                status,
                STATUS_TITLES[status] ?: status,
                rs.getInt("n"),
                rs.getObject("avg_min")?.let {
                    (it as Number).toDouble()
                },
                rs.getObject("median_min")?.let { (it as Number).toDouble() },
            )
        }.sortedBy { STATUS_ORDER.indexOf(it.status).let { index -> if (index < 0) Int.MAX_VALUE else index } }

    private fun outcomes(kind: String, period: Map<String, Any>): List<OutcomeCount> =
        jdbc.query(
            """
            select outcome, count(*) as n from decisions
            where created_at >= :from and created_at < :to and ${if (kind == "SYSTEM") "decided_by_type = 'SYSTEM'" else "decided_by_type <> 'SYSTEM'"}
            group by outcome order by count(*) desc
            """.trimIndent(),
            period,
        ) { rs, _ -> OutcomeCount(rs.getString("outcome"), rs.getInt("n")) }

    private fun overrides(period: Map<String, Any>): Int =
        jdbc.queryForObject(
            """
            select count(*) from decisions
            where created_at >= :from and created_at < :to and decided_by_type = 'UNDERWRITER'
              and outcome in ('APPROVE', 'COUNTER_OFFER', 'REJECT')
              and (recommendation is null or recommendation ->> 'outcome' <> outcome)
            """.trimIndent(),
            period,
            Int::class.java,
        )!!

    private companion object {
        val STATUS_ORDER = listOf("SUBMITTED", "INFO_REQUESTED", "ENRICHMENT", "SCORING", "MANUAL_REVIEW", "OFFER_ISSUED", "SIGNING", "DISBURSING")
        val STATUS_TITLES = mapOf(
            "SUBMITTED" to "Проверка полноты",
            "INFO_REQUESTED" to "Ожидание документов от клиента",
            "ENRICHMENT" to "Запрос источников",
            "SCORING" to "Скоринг",
            "MANUAL_REVIEW" to "Ручное рассмотрение",
            "OFFER_ISSUED" to "Ожидание ответа на предложение",
            "SIGNING" to "Подписание",
            "DISBURSING" to "Выдача",
        )
    }
}
