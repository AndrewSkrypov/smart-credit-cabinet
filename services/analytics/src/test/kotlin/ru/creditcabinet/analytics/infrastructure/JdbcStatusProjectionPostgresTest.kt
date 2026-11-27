package ru.creditcabinet.analytics.infrastructure

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import ru.creditcabinet.analytics.application.StatusUpdateResult
import ru.creditcabinet.analytics.domain.ApplicationStatus
import ru.creditcabinet.analytics.domain.StatusEvent
import ru.creditcabinet.analytics.domain.VersionedStatus
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@EnabledIfEnvironmentVariable(named = "ANALYTICS_TEST_JDBC_URL", matches = ".+")
class JdbcStatusProjectionPostgresTest {
    private val schema = "analytics_test_${UUID.randomUUID().toString().replace("-", "")}"
    private lateinit var base: DriverManagerDataSource
    private lateinit var database: DriverManagerDataSource
    private lateinit var sql: JdbcTemplate
    private lateinit var projection: JdbcStatusProjection

    @BeforeTest
    fun migrate() {
        val url = checkNotNull(System.getenv("ANALYTICS_TEST_JDBC_URL"))
        val user = System.getenv("ANALYTICS_TEST_DB_USER") ?: "cabinet"
        val password = System.getenv("ANALYTICS_TEST_DB_PASSWORD") ?: ""
        base = DriverManagerDataSource(url, user, password)
        Flyway.configure().dataSource(base).schemas(schema).load().migrate()
        val separator = if ("?" in url) "&" else "?"
        database = DriverManagerDataSource("$url${separator}currentSchema=$schema", user, password)
        sql = JdbcTemplate(database)
        projection = restart()
    }

    @AfterTest
    fun dropTestSchema() {
        if (::base.isInitialized) JdbcTemplate(base).execute("DROP SCHEMA IF EXISTS $schema CASCADE")
    }

    @Test
    fun `snapshot overlap and replay preserve the highest version and exact visible counts`() {
        val applicationId = UUID.randomUUID()
        val newest = event(8, ApplicationStatus.WITHDRAWN, applicationId)
        val older = event(2, ApplicationStatus.PENDING_CLIENT_CONFIRMATION, applicationId)
        val snapshotOnly = state(4, ApplicationStatus.REJECTED)

        assertEquals(StatusUpdateResult.APPLIED, projection.applyEvent(newest))
        assertEquals(StatusUpdateResult.STALE, projection.applyEvent(older))
        assertEquals(StatusUpdateResult.APPLIED, projection.applySnapshot(snapshotOnly))
        assertEquals(StatusUpdateResult.UNCHANGED, projection.applySnapshot(snapshotOnly))
        assertEquals(StatusUpdateResult.UNCHANGED, projection.applySnapshot(older.state))
        assertEquals(StatusUpdateResult.APPLIED, projection.applySnapshot(state(0, ApplicationStatus.DRAFT)))
        assertEquals(StatusUpdateResult.APPLIED, projection.applySnapshot(state(1, ApplicationStatus.PENDING_CLIENT_CONFIRMATION)))
        assertEquals(StatusUpdateResult.DUPLICATE, restart().applyEvent(newest))
        assertEquals(mapOf("WITHDRAWN" to 1, "REJECTED" to 1), projection.currentStatuses())
        assertEquals(
            8L,
            sql.queryForObject("SELECT application_version FROM application_status_projection WHERE application_id = ?", Long::class.java, applicationId),
        )
        assertEquals(2, rowCount("application_status_receipts"))
    }

    @Test
    fun `diagnoses a historical status conflict and reuse of a journal id without replacing the current status`() {
        val applicationId = UUID.randomUUID()
        val first = event(4, ApplicationStatus.SCORING, applicationId)
        val newest = event(12, ApplicationStatus.DISBURSED, applicationId)
        val conflict = event(4, ApplicationStatus.REJECTED, applicationId)

        assertEquals(StatusUpdateResult.APPLIED, projection.applyEvent(first))
        assertEquals(StatusUpdateResult.APPLIED, projection.applyEvent(newest))
        assertEquals(StatusUpdateResult.VERSION_CONFLICT, projection.applyEvent(conflict))
        assertEquals(StatusUpdateResult.VERSION_CONFLICT, restart().applyEvent(conflict))
        assertEquals(StatusUpdateResult.EVENT_ID_CONFLICT, projection.applyEvent(first.copy(occurredAt = first.occurredAt.plusNanos(1))))
        assertEquals(mapOf("DISBURSED" to 1), projection.currentStatuses())
        assertEquals(3, rowCount("application_status_receipts"))
    }

    @Test
    fun `rollback erases the receipt and version before a successful retry`() {
        val status = event(3, ApplicationStatus.SUBMITTED)
        sql.execute("ALTER TABLE application_status_projection ADD CONSTRAINT test_failure CHECK (status <> 'SUBMITTED')")

        assertFailsWith<DataIntegrityViolationException> { projection.applyEvent(status) }
        assertEquals(0, rowCount("application_status_receipts"))
        assertEquals(0, rowCount("application_status_versions"))
        assertEquals(emptyMap(), projection.currentStatuses())

        sql.execute("ALTER TABLE application_status_projection DROP CONSTRAINT test_failure")
        assertEquals(StatusUpdateResult.APPLIED, projection.applyEvent(status))
        assertEquals(StatusUpdateResult.DUPLICATE, restart().applyEvent(status))
        assertEquals(mapOf("SUBMITTED" to 1), projection.currentStatuses())
    }

    @Test
    fun `competing events at the same version agree on one status`() {
        val applicationId = UUID.randomUUID()
        val versions = listOf(event(4, ApplicationStatus.SCORING, applicationId), event(4, ApplicationStatus.WITHDRAWN, applicationId))
        val start = CountDownLatch(1)
        Executors.newFixedThreadPool(versions.size).use { workers ->
            val attempts = versions.map { event ->
                workers.submit<StatusUpdateResult> {
                    check(start.await(5, TimeUnit.SECONDS))
                    projection.applyEvent(event)
                }
            }
            start.countDown()
            val outcomes = attempts.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(setOf(StatusUpdateResult.APPLIED, StatusUpdateResult.VERSION_CONFLICT), outcomes.toSet())
            assertEquals(mapOf(versions[outcomes.indexOf(StatusUpdateResult.APPLIED)].state.status.name to 1), projection.currentStatuses())
        }
        assertEquals(2, rowCount("application_status_receipts"))
        assertEquals(1, rowCount("application_status_versions"))
    }

    private fun restart() = JdbcStatusProjection(NamedParameterJdbcTemplate(database), DataSourceTransactionManager(database))

    private fun rowCount(table: String): Int? = sql.queryForObject("SELECT count(*) FROM $table", Int::class.java)

    private fun state(version: Long, status: ApplicationStatus, id: UUID = UUID.randomUUID()) = VersionedStatus(id, version, status)

    private fun event(version: Long, status: ApplicationStatus, id: UUID = UUID.randomUUID()) =
        StatusEvent(UUID.randomUUID(), state(version, status, id), Instant.parse("2026-11-26T12:15:11.123456789Z"))
}
