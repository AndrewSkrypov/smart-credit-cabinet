package ru.creditcabinet.document

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.infrastructure.SpringCreditTransactions
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.generated.ArtifactInput
import ru.creditcabinet.document.generated.ArtifactPhase
import ru.creditcabinet.document.generated.ArtifactRenderer
import ru.creditcabinet.document.generated.application.ArtifactView
import ru.creditcabinet.document.generated.application.GeneratedArtifacts
import ru.creditcabinet.document.generated.infrastructure.DefaultGeneratedArtifactRendering
import ru.creditcabinet.document.generated.infrastructure.DocumentsGeneratedArtifactRemote
import ru.creditcabinet.document.generated.infrastructure.JacksonArtifactInputCodec
import ru.creditcabinet.document.generated.infrastructure.JdbcGeneratedArtifactStore
import ru.creditcabinet.document.infrastructure.DocumentsBoundary
import java.io.File
import java.time.Clock
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Real Credit transaction + standalone Documents mTLS, not a distributed transaction mock. */
class DocumentsArtifactIntentRuntimeTest {
    @Test
    fun `outer rollback sends no prepare while committed intent survives publisher restart and concurrent Credit replay`() {
        val source =
            DriverManagerDataSource(env("DOCUMENTS_TEST_DATABASE_BASE") + "/credit", env("DOCUMENTS_TEST_ADMIN_USER"), env("DOCUMENTS_TEST_ADMIN_PASSWORD"))
        val jdbc = JdbcTemplate(source)
        val mapper = jacksonObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        val tx = TransactionTemplate(DataSourceTransactionManager(source))
        val applicationId = UUID.fromString(env("DOCUMENTS_TEST_APPLICATION"))
        val original = mapper.readValue(
            jdbc.queryForObject(
                "select input_json from document_artifact_inputs where application_id=? and phase='FINAL' order by created_at limit 1",
                String::class.java,
                applicationId,
            ),
            ArtifactInput::class.java,
        )
        val offer = UUID.randomUUID()
        jdbc.update(
            "insert into offers(id,application_id,decision_id,kind,product_code,amount,term_months,rate,monthly_payment,total_payment,valid_until,status,created_at,product_version) select ?,application_id,decision_id,kind,product_code,amount,term_months,rate,monthly_payment,total_payment,valid_until,status,created_at,product_version from offers where id=?",
            offer,
            original.offerId,
        )
        val input = original.copy(
            offerId = offer,
            phase = ArtifactPhase.PREVIEW,
            contractText = "",
            contractTextHash = null,
            signedAt = null,
            contractNumber = null,
        )
        val certs = File(env("DOCUMENTS_TEST_CERTS"))
        val boundary =
            DocumentsBoundary(
                mock(), jdbc, mapper, "remote", 2, "127.0.0.1",
                env(
                    "DOCUMENTS_TEST_GRPC_PORT",
                ).toInt(),
                certs.resolve("credit.crt").path, certs.resolve("credit.key").path, certs.resolve("ca.crt").path, 10000,
            )
        val application = mock<LoanApplication>()
        whenever(application.id).thenReturn(applicationId)
        whenever(application.number).thenReturn(1L)
        val repository = mock<ru.creditcabinet.application.domain.ApplicationRepository>()
        whenever(repository.find(applicationId)).thenReturn(application)
        val renderer = mock<ArtifactRenderer>()
        whenever(renderer.title(any(), any())).thenReturn("Intent protocol")
        val files = mock<FileStore>()
        whenever(files.writeBackend).thenReturn(StorageBackend.valueOf(env("DOCUMENTS_TEST_STORAGE_BACKEND")))
        val codec = JacksonArtifactInputCodec(mapper)
        fun service() =
            GeneratedArtifacts(
                JdbcGeneratedArtifactStore(jdbc, codec),
                codec,
                mock(),
                repository,
                mock(),
                mock(),
                mock(),
                mock(),
                mock(),
                files,
                DefaultGeneratedArtifactRendering(renderer),
                SpringCreditTransactions(tx),
                Clock.systemUTC(),
                mock(),
                DocumentsGeneratedArtifactRemote(boundary),
                mock(),
            )
        val prepare = GeneratedArtifacts::class.java.getDeclaredMethod("prepareRemote", LoanApplication::class.java, ArtifactInput::class.java).apply {
            isAccessible =
                true
        }
        val publish = GeneratedArtifacts::class.java.getDeclaredMethod("publishPendingRemote").apply { isAccessible = true }
        val scope = boundary.scope(applicationId)
        try {
            assertFailsWith<IllegalStateException> {
                tx.executeWithoutResult {
                    prepare.invoke(service(), application, input)
                    assertTrue(boundary.client().artifacts(scope).none { it.offerId == offer })
                    throw IllegalStateException("Synthetic business rollback")
                }
            }
            assertEquals(0, jdbc.queryForObject("select count(*) from document_artifact_inputs where offer_id=?", Int::class.java, offer))
            assertEquals(0, jdbc.queryForObject("select count(*) from document_artifact_references where offer_id=?", Int::class.java, offer))
            assertTrue(boundary.client().artifacts(scope).none { it.offerId == offer })
            // Commit a durable intent without an RPC, then use a new publisher object.
            tx.executeWithoutResult { prepare.invoke(service(), application, input) }
            publish.invoke(service())
            val executor = Executors.newFixedThreadPool(2)
            try {
                val replies = (1..2).map {
                    executor.submit<List<*>> { prepare.invoke(service(), application, input) as List<*> }
                }.map { it.get(15, TimeUnit.SECONDS) }
                assertEquals(replies[0].map { (it as ArtifactView).id }, replies[1].map { (it as ArtifactView).id })
            } finally {
                executor.shutdownNow()
                executor.awaitTermination(10, TimeUnit.SECONDS)
            }
            assertEquals(2, boundary.client().artifacts(scope).count { it.offerId == offer })
            assertEquals(
                1,
                jdbc.queryForObject("select count(*) from document_artifact_inputs where offer_id=? and delivered_at is not null", Int::class.java, offer),
            )
            assertEquals(1, jdbc.queryForObject("select count(*) from document_artifact_references where offer_id=?", Int::class.java, offer))
        } finally {
            boundary.close()
        }
    }
    private fun env(key: String): String = System.getenv(key)?.takeIf { it.isNotBlank() } ?: error("Required runtime configuration: $key")
}
