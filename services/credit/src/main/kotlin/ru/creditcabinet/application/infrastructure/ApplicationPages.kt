package ru.creditcabinet.application.infrastructure

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.common.security.CurrentCaller
import java.util.UUID

data class ApplicationPageRows(
    val items: List<LoanApplication>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean,
    val countsByGroup: Map<String, Long>,
    val actionRequiredCount: Long,
    val actionRequiredPreview: List<LoanApplication>,
)

/** SQL limits IDs before materializing entities; the legacy array endpoint is unchanged. */
@Component
class ApplicationPages(private val jdbc: NamedParameterJdbcTemplate, private val repository: ApplicationJpaRepository) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun read(page: Long, size: Int, group: String?, q: String, sort: String): ApplicationPageRows {
        require(size in 1..100 && page >= 0 && page <= Int.MAX_VALUE / size) { "Недопустимый номер или размер страницы" }
        val query = q.trim()
        require(query.length <= 100) { "Поиск должен содержать не больше 100 символов" }
        require(sort in setOf("default", "oldest", "newest", "number_desc", "number_asc")) { "Неизвестная сортировка" }
        val claims = CurrentCaller.claims()
        val client = claims.isClient
        val companyId = if (client) CurrentCaller.requireClient().second else null
        if (!client) CurrentCaller.requireBankStaff()
        val groups = if (client) {
            mapOf(
                "active" to ACTIVE,
                "done" to DONE,
                "action" to (if (claims.canSign) ACTION else ACTION - "SIGNING"),
            )
        } else {
            mapOf("review" to listOf("MANUAL_REVIEW"), "client" to listOf("INFO_REQUESTED", "OFFER_ISSUED", "SIGNING"), "system" to SYSTEM, "done" to DONE)
        }
        val selected = group ?: if (client) "active" else "review"
        require(selected in groups) { "Неизвестная группа заявок для этой роли" }
        val rights = if (client) "a.company_id = :company" else "a.status <> 'DRAFT'"
        val columns = if (client) {
            "lower(p.name) like :q escape '!'"
        } else {
            "(lower(coalesce(a.company_name, '')) like :q escape '!' or a.company_inn like :q escape '!')"
        }
        val search = if (query.isEmpty()) "true" else "(cast(a.number as text) like :number escape '!' or $columns)"
        fun literal(value: String) = "%" + value.lowercase().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
        val params = mutableMapOf<String, Any?>(
            "company" to companyId,
            "q" to literal(query),
            "number" to literal(query.removePrefix("№").trim()),
            "statuses" to groups.getValue(selected),
            "size" to size,
        )
        val from = "from applications a join product_versions p on p.code = a.product_code and p.version = a.product_version"
        val statusCounts = jdbc.query("select a.status, count(*) as n $from where $rights and $search group by a.status", params) { row, _ ->
            row.getString("status") to
                row.getLong("n")
        }.toMap()
        val counts = groups.mapValues { (_, statuses) -> statuses.sumOf { statusCounts[it] ?: 0L } }
        val total = counts.getValue(selected)
        val totalPages = ((total + size - 1) / size).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val actualPage = page.coerceAtMost((totalPages - 1).coerceAtLeast(0).toLong()).toInt()
        params["offset"] = actualPage.toLong() * size
        val ascending = sort == "oldest" || sort == "number_asc" || (sort == "default" && !client && selected != "done")
        val direction = if (ascending) "asc" else "desc"
        val field = when (sort) {
            "number_desc", "number_asc" -> "a.number"
            "default" -> if (client) "a.created_at" else "a.status_changed_at"
            else -> "a.status_changed_at"
        }
        val ids = jdbc.query(
            "select a.id $from where $rights and $search and a.status in (:statuses) order by $field $direction, a.id $direction limit :size offset :offset",
            params,
        ) {
                row,
                _,
            ->
            row.getObject("id", UUID::class.java)
        }
        val actionParams = mapOf("company" to companyId, "action" to groups["action"].orEmpty())
        val actionCount = if (client) {
            jdbc.queryForObject("select count(*) from applications where company_id = :company and status in (:action)", actionParams, Long::class.java)
                ?: 0
        } else {
            0
        }
        val previewIds = if (client) {
            jdbc.query(
                "select id from applications where company_id = :company and status in (:action) order by status_changed_at asc, id asc limit 5",
                actionParams,
            ) {
                    row,
                    _,
                ->
                row.getObject("id", UUID::class.java)
            }
        } else {
            emptyList()
        }
        val entities = repository.findAllById((ids + previewIds).distinct()).associate { it.id to it.toDomain() }
        return ApplicationPageRows(
            ids.map {
                entities.getValue(it)
            },
            actualPage, size, total, totalPages, actualPage + 1 < totalPages, counts, actionCount, previewIds.map { entities.getValue(it) },
        )
    }

    companion object {
        private val DONE = listOf("DISBURSED", "REJECTED", "WITHDRAWN", "CLOSED")
        private val SYSTEM = listOf("SUBMITTED", "ENRICHMENT", "SCORING", "DISBURSING")
        private val ACTIVE = listOf("DRAFT", "INFO_REQUESTED", "OFFER_ISSUED", "SIGNING", "MANUAL_REVIEW") + SYSTEM
        private val ACTION = listOf("DRAFT", "INFO_REQUESTED", "OFFER_ISSUED", "SIGNING")
    }
}
