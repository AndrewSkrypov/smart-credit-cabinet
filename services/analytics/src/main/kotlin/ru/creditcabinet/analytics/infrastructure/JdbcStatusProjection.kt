package ru.creditcabinet.analytics.infrastructure

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.analytics.application.CurrentStatuses
import ru.creditcabinet.analytics.application.StatusUpdateResult
import ru.creditcabinet.analytics.application.UpdateStatusProjection
import ru.creditcabinet.analytics.domain.ApplicationStatus
import ru.creditcabinet.analytics.domain.StatusEvent
import ru.creditcabinet.analytics.domain.VersionedStatus

@Repository
class JdbcStatusProjection(
    private val jdbc: NamedParameterJdbcTemplate,
    transactions: PlatformTransactionManager,
) : UpdateStatusProjection, CurrentStatuses {
    private val transaction = TransactionTemplate(transactions).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
        isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
    }

    override fun applyEvent(event: StatusEvent): StatusUpdateResult = checkNotNull(
        transaction.execute {
            val parameters = stateParameters(event.state)
                .addValue("eventId", event.eventId)
                .addValue("occurredAt", event.occurredAt.toString())
            val newReceipt = jdbc.update(
                """
                INSERT INTO application_status_receipts (event_id, application_id, application_version, status, occurred_at)
                VALUES (:eventId, :applicationId, :applicationVersion, :status, :occurredAt)
                ON CONFLICT (event_id) DO NOTHING
                """.trimIndent(),
                parameters,
            ) == 1
            when {
                newReceipt -> applyState(event.state, parameters)
                !matchesReceipt(parameters) -> StatusUpdateResult.EVENT_ID_CONFLICT
                !matchesObservedVersion(event.state, parameters) -> StatusUpdateResult.VERSION_CONFLICT
                else -> StatusUpdateResult.DUPLICATE
            }
        },
    )

    override fun applySnapshot(state: VersionedStatus): StatusUpdateResult = checkNotNull(
        transaction.execute { applyState(state, stateParameters(state)) },
    )

    override fun currentStatuses(): Map<String, Int> =
        jdbc.query(
            """
            SELECT status, count(*) AS n
            FROM application_status_projection
            WHERE status IN (:visibleStatuses)
            GROUP BY status
            """.trimIndent(),
            mapOf("visibleStatuses" to ApplicationStatus.entries.filter(ApplicationStatus::visibleInFunnel).map(ApplicationStatus::name)),
        ) { row, _ -> row.getString("status") to row.getInt("n") }.toMap()

    private fun applyState(state: VersionedStatus, parameters: MapSqlParameterSource): StatusUpdateResult {
        val newVersion = jdbc.update(
            """
            INSERT INTO application_status_versions (application_id, application_version, status)
            VALUES (:applicationId, :applicationVersion, :status)
            ON CONFLICT (application_id, application_version) DO NOTHING
            """.trimIndent(),
            parameters,
        ) == 1
        if (!newVersion) {
            // This statement reads the winning status after a concurrent insert has committed.
            return if (matchesObservedVersion(state, parameters)) StatusUpdateResult.UNCHANGED else StatusUpdateResult.VERSION_CONFLICT
        }

        val applied = jdbc.update(
            """
            INSERT INTO application_status_projection (application_id, application_version, status)
            VALUES (:applicationId, :applicationVersion, :status)
            ON CONFLICT (application_id) DO UPDATE
                SET application_version = EXCLUDED.application_version, status = EXCLUDED.status
                WHERE application_status_projection.application_version < EXCLUDED.application_version
            """.trimIndent(),
            parameters,
        ) == 1
        return if (applied) StatusUpdateResult.APPLIED else StatusUpdateResult.STALE
    }

    private fun matchesObservedVersion(state: VersionedStatus, parameters: MapSqlParameterSource): Boolean =
        jdbc.queryForObject(
            """
            SELECT status
            FROM application_status_versions
            WHERE application_id = :applicationId AND application_version = :applicationVersion
            """.trimIndent(),
            parameters,
            String::class.java,
        ) == state.status.name

    private fun matchesReceipt(parameters: MapSqlParameterSource): Boolean =
        jdbc.queryForObject(
            """
            SELECT application_id = :applicationId
               AND application_version = :applicationVersion
               AND status = :status
               AND occurred_at = :occurredAt
            FROM application_status_receipts
            WHERE event_id = :eventId
            """.trimIndent(),
            parameters,
            Boolean::class.java,
        ) == true

    private fun stateParameters(state: VersionedStatus) =
        MapSqlParameterSource()
            .addValue("applicationId", state.applicationId)
            .addValue("applicationVersion", state.applicationVersion)
            .addValue("status", state.status.name)
}
