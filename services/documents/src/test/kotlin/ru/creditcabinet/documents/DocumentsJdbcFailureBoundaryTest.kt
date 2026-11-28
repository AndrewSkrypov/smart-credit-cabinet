package ru.creditcabinet.documents

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.mockito.Mockito
import org.springframework.jdbc.CannotGetJdbcConnectionException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.documents.application.ArtifactClaim
import ru.creditcabinet.documents.application.DocumentsOwnership
import ru.creditcabinet.documents.application.DocumentsStorageUnavailable
import ru.creditcabinet.documents.domain.ArtifactLifecycle
import ru.creditcabinet.documents.infrastructure.JdbcArtifactJobs
import ru.creditcabinet.documents.infrastructure.JdbcDocumentMetadata
import ru.creditcabinet.documents.infrastructure.JdbcDocumentsRepository
import java.sql.SQLException
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DocumentsJdbcFailureBoundaryTest {
    @Test
    fun `every JDBC outbound port method translates driver failures exactly once`() {
        val raw = CannotGetJdbcConnectionException("Synthetic driver failure", SQLException("Synthetic disconnect", "08006"))
        val jdbc = Mockito.mock(JdbcTemplate::class.java) { throw raw }
        val tx = callbackTransactions()
        val mapper = jacksonObjectMapper().findAndRegisterModules()
        val repository = JdbcDocumentsRepository(jdbc, mapper, tx)
        val metadata = JdbcDocumentMetadata(jdbc, mapper)
        val ownership = object : DocumentsOwnership {
            override fun <T> owned(epoch: Long, action: () -> T): T = action()
        }
        val jobs = JdbcArtifactJobs(jdbc, mapper, tx, ownership)
        val id = UUID.randomUUID()
        val scope = DocumentScope(UUID.randomUUID(), UUID.randomUUID())
        val document = DocumentRecord(id, scope, "OTHER", "test.pdf", "test.pdf", 1, id, Instant.EPOCH, "BUSINESS", "LOCAL", "0".repeat(64), false)
        val artifact = ArtifactRecord(
            id, scope, id, "SCHEDULE_PDF", "PREVIEW", 2,
            "0".repeat(
                64,
            ),
            "{}", "Test", "test.pdf", "application/pdf", "generated-$id.pdf", "LOCAL", Instant.EPOCH,
        )
        val claim = ArtifactClaim(artifact, UUID.randomUUID(), 7)
        val actions = listOf<() -> Any?>(
            { repository.owned(7) { error("must not enter after ownership query failure") } },
            { repository.verifyScope(scope, bind = true) },
            { repository.documents(scope) },
            { repository.saveDeleted(document.copy(deletedAt = Instant.EPOCH)) },
            { repository.artifacts(scope) },
            { repository.insertArtifact(artifact) },
            { repository.lockArtifact(scope, id) },
            { repository.retireExpiredLease(id) },
            { repository.resetPending(artifact) },
            { metadata.find(id) },
            { metadata.reserve(document) },
            { metadata.insert(document) },
            { jobs.claim(ArtifactLifecycle(2)) },
            { jobs.publish(claim) { error("must not write bytes after lease query failure") } },
            { jobs.fail(claim, 15) },
        )
        for (action in actions) assertSame(raw, assertFailsWith<DocumentsStorageUnavailable> { action() }.cause)
        assertEquals(15, actions.size)
    }

    @Test
    fun `ownership boundary also translates failures during begin and after successful action before commit`() {
        for (afterAction in listOf(false, true)) {
            val raw = CannotGetJdbcConnectionException("Synthetic transaction failure", SQLException("Synthetic disconnect", "08006"))
            val jdbc = Mockito.mock(JdbcTemplate::class.java)
            Mockito.`when`(jdbc.queryForMap("select owner,epoch from document_ownership where singleton")).thenReturn(
                mapOf(
                    "owner" to "DOCUMENTS",
                    "epoch" to 7L,
                ),
            )
            val tx = Mockito.mock(TransactionTemplate::class.java)
            var visits = 0
            Mockito.`when`(tx.execute(callbackMatcher())).thenAnswer { invocation ->
                if (afterAction) invocation.getArgument<TransactionCallback<Any>>(0).doInTransaction(SimpleTransactionStatus())
                throw raw
            }
            val repository = JdbcDocumentsRepository(jdbc, jacksonObjectMapper(), tx)
            assertSame(raw, assertFailsWith<DocumentsStorageUnavailable> { repository.owned(7) { visits++ } }.cause)
            assertEquals(if (afterAction) 1 else 0, visits)
        }
    }

    private fun callbackMatcher(): TransactionCallback<Any> =
        Mockito.any<TransactionCallback<Any>>() ?: TransactionCallback { Any() }

    private fun callbackTransactions(): TransactionTemplate = Mockito.mock(TransactionTemplate::class.java).also { tx ->
        Mockito.`when`(tx.execute(callbackMatcher())).thenAnswer { invocation ->
            invocation.getArgument<TransactionCallback<Any>>(0).doInTransaction(SimpleTransactionStatus())
        }
    }
}
