package ru.creditcabinet.financing.scenario

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.creditcabinet.application.domain.ApplicationTerms
import java.util.UUID

internal data class ScenarioRevision(
    val productVersion: Long,
    val snapshot: FinancingScenarioSnapshot,
    val documentIds: Set<UUID> = emptySet(),
    val handedTerms: ApplicationTerms? = null,
)

@Repository
class FinancingScenarioStore(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    internal fun latest(applicationId: UUID, handedOnly: Boolean = false): ScenarioRevision? =
        recent(applicationId, handedOnly, 1).singleOrNull()

    internal fun recent(applicationId: UUID, handedOnly: Boolean, limit: Int): List<ScenarioRevision> =
        jdbc.query(
            """
            select r.product_version, r.snapshot::text, h.document_ids::text, h.handed_terms::text,
                case when c.revision is not null then 'CONFIRMED'
                     when h.revision is not null then 'AWAITING_CLIENT'
                     else 'DRAFT' end as current_status
            from financing_scenario_revisions r
            left join financing_scenario_publications h
                on h.application_id = r.application_id and h.revision = r.revision and h.status = 'AWAITING_CLIENT'
            left join financing_scenario_publications c
                on c.application_id = r.application_id and c.revision = r.revision and c.status = 'CONFIRMED'
            where r.application_id = ? and (not ? or h.revision is not null)
            order by r.revision desc limit ?
            """.trimIndent(),
            { row, _ ->
                val snapshot = mapper.readValue(row.getString("snapshot"), FinancingScenarioSnapshot::class.java)
                val status = FinancingScenarioStatus.valueOf(row.getString("current_status"))
                val documentIds = row.getString("document_ids")?.let(mapper::readTree)?.map { UUID.fromString(it.asText()) }.orEmpty().toSet()
                val handedTerms = row.getString("handed_terms")?.let { mapper.readValue(it, ApplicationTerms::class.java) }
                ScenarioRevision(row.getLong("product_version"), snapshot.copy(status = status), documentIds, handedTerms)
            },
            applicationId,
            handedOnly,
            limit,
        )

    internal fun append(applicationId: UUID, revision: ScenarioRevision) {
        jdbc.update(
            """
            insert into financing_scenario_revisions(application_id, revision, product_version, snapshot)
            values (?, ?, ?, cast(? as jsonb))
            """.trimIndent(),
            applicationId,
            revision.snapshot.revision,
            revision.productVersion,
            mapper.writeValueAsString(revision.snapshot),
        )
    }

    internal fun publish(
        applicationId: UUID,
        revision: Long,
        status: FinancingScenarioStatus,
        documents: List<UUID> = emptyList(),
        handedTerms: ApplicationTerms? = null,
    ) {
        jdbc.update(
            """
            insert into financing_scenario_publications(application_id, revision, status, document_ids, handed_terms)
            values (?, ?, ?, cast(? as jsonb), cast(? as jsonb))
            """.trimIndent(),
            applicationId,
            revision,
            status.name,
            mapper.writeValueAsString(documents),
            handedTerms?.let(mapper::writeValueAsString),
        )
    }
}
