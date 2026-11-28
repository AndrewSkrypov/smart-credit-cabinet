package ru.creditcabinet.financing.scenario

import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

internal fun creditMcpSnapshot(jdbc: JdbcTemplate, id: UUID): List<List<String>> {
    val scopedTables = listOf(
        "financing_scenario_revisions",
        "financing_scenario_publications",
        "documents",
        "application_events",
        "assessment_exchange_outbox",
        "servicing_open_outbox",
    )
    val application = jdbc.queryForList("select version::text from applications where id = ?", String::class.java, id)
    val scoped = scopedTables.map { table ->
        jdbc.queryForList("select to_jsonb(r)::text from $table r where application_id = ? order by 1", String::class.java, id)
    }
    val outbox = listOf("application_status_outbox", "delivery_outbox").map { table ->
        jdbc.queryForList(
            "select to_jsonb(r)::text from $table r where event_key like ? or payload::text like ? order by 1",
            String::class.java,
            "%$id%",
            "%$id%",
        )
    }
    return listOf(application) + scoped + outbox
}
