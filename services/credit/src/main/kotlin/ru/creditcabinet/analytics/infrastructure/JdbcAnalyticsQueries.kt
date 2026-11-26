package ru.creditcabinet.analytics.infrastructure

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import ru.creditcabinet.analytics.application.Analytics
import ru.creditcabinet.analytics.application.AnalyticsQueries
import ru.creditcabinet.analytics.application.FunnelStep
import ru.creditcabinet.analytics.application.OutcomeCount
import ru.creditcabinet.analytics.application.StageTime
import java.sql.Timestamp
import java.time.Instant

@Repository
class JdbcAnalyticsQueries(private val jdbc: NamedParameterJdbcTemplate) : AnalyticsQueries {
    override fun read(from: Instant, to: Instant, projectedStatuses: Map<String, Int>?): Analytics {
        val period = mapOf("from" to Timestamp.from(from), "to" to Timestamp.from(to))
        return Analytics(
            from = from,
            to = to,
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
            currentStatuses = projectedStatuses ?: jdbc.query(
                "select status, count(*) as n from applications where status not in ('DRAFT', 'PENDING_CLIENT_CONFIRMATION') group by status",
                emptyMap<String, Any>(),
            ) {
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
            where left_at is not null and to_status not in ('DRAFT', 'PENDING_CLIENT_CONFIRMATION') and occurred_at >= :from and occurred_at < :to
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
