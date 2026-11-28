package ru.creditcabinet.documents

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.document.domain.DocumentScanner
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.generated.ArtifactInput
import ru.creditcabinet.document.generated.ArtifactPhase
import ru.creditcabinet.document.generated.ArtifactRenderer
import ru.creditcabinet.documents.infrastructure.DocumentsConfiguration
import ru.creditcabinet.documents.infrastructure.JdbcDocumentMetadata
import ru.creditcabinet.product.domain.LoanMath
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Isolated owned fixture DB avoids background workers racing the controlled lease checkpoint. */
class DocumentsWorkerLeaseRuntimeTest {
    @Test
    fun `late READY and ERROR callbacks cannot write after retry token retirement or expiry during row-lock wait`() {
        val source =
            DriverManagerDataSource(
                env("DOCUMENTS_TEST_DATABASE_BASE") + "/documents_lease",
                env("DOCUMENTS_TEST_ADMIN_USER"),
                env("DOCUMENTS_TEST_ADMIN_PASSWORD"),
            )
        val jdbc = JdbcTemplate(source)
        val tx = TransactionTemplate(DataSourceTransactionManager(source))
        val mapper = jacksonObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val scope = DocumentScope(UUID.randomUUID(), UUID.randomUUID())
        for (outcome in listOf("READY", "ERROR")) {
            for (retirement in listOf("retry", "expired-after-lock")) {
                val id = UUID.randomUUID()
                val input =
                    ArtifactInput(
                        scope.applicationId, 1, UUID.randomUUID(), "Lease proof", "000100000004", "Product", 100000, 6,
                        LoanMath.calculate(
                            100000,
                            BigDecimal("12.00"),
                            6,
                            false,
                        ),
                        ArtifactPhase.PREVIEW, "", null, false, null, null, Instant.EPOCH,
                    )
                val json = mapper.writeValueAsString(input)
                val record =
                    ArtifactRecord(
                        id, scope, input.offerId, "SCHEDULE_PDF", "PREVIEW", 2,
                        sha256(
                            json.toByteArray(),
                        ),
                        json, "Lease proof", "schedule.pdf", "application/pdf", "generated-$id.pdf", "LOCAL", Instant.EPOCH,
                    )
                jdbc.update(
                    "insert into artifact_records(id,company_id,application_id,offer_id,kind,phase,template_version,input_hash,metadata) values(?,?,?,?,'SCHEDULE_PDF','PREVIEW',2,?,?::jsonb)",
                    id,
                    scope.companyId,
                    scope.applicationId,
                    input.offerId,
                    record.inputHash,
                    mapper.writeValueAsString(record),
                )
                val entered = CountDownLatch(1)
                val release = CountDownLatch(1)
                val renderer = mock<ArtifactRenderer>()
                doAnswer {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                    if (outcome == "ERROR") throw java.io.IOException("Synthetic render failure")
                    "%PDF-1.4\nlease\n%%EOF".toByteArray()
                }.whenever(renderer).render(any(), any())
                val files = mock<FileStore>()
                val store = DocumentsConfiguration().documentsStore(jdbc, mapper, tx, files, DocumentScanner {}, renderer, JdbcDocumentMetadata(jdbc, mapper))
                val executor = Executors.newSingleThreadExecutor()
                try {
                    val worker = executor.submit { store.generateOne() }
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                    if (retirement == "retry") {
                        jdbc.update("update artifact_records set lease_until=clock_timestamp()-interval '1 second' where id=?", id)
                        store.owned(1) { store.retry(scope, id) }
                        assertEquals(null, jdbc.queryForMap("select lease_token from artifact_records where id=?", id)["lease_token"])
                        release.countDown()
                    } else {
                        source.connection.use { blocker ->
                            blocker.autoCommit = false
                            blocker.prepareStatement("update artifact_records set lease_until=clock_timestamp()+interval '1 second' where id=?").use {
                                it.setObject(1, id)
                                it.executeUpdate()
                            }
                            release.countDown()
                            Thread.sleep(1500)
                            assertTrue(!worker.isDone)
                            blocker.commit()
                        }
                    }
                    worker.get(5, TimeUnit.SECONDS)
                    verify(files, never()).put(any(), any(), any(), any())
                    assertEquals("PENDING", jdbc.queryForObject("select status from artifact_records where id=?", String::class.java, id))
                    assertEquals(0, jdbc.queryForObject("select count(*) from artifact_outbox where artifact_id=?", Int::class.java, id))
                } finally {
                    release.countDown()
                    executor.shutdownNow()
                    executor.awaitTermination(5, TimeUnit.SECONDS)
                    jdbc.update("delete from artifact_outbox where artifact_id=?", id)
                    jdbc.update("delete from artifact_records where id=?", id)
                }
            }
        }
    }
    private fun env(key: String): String = System.getenv(key)?.takeIf { it.isNotBlank() } ?: error("Required runtime configuration: $key")
}
