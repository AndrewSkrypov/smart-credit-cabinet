package ru.creditcabinet.documents

import ru.creditcabinet.document.domain.DocumentScanner
import ru.creditcabinet.documents.application.ArtifactClaim
import ru.creditcabinet.documents.application.ArtifactJobs
import ru.creditcabinet.documents.application.ArtifactOperations
import ru.creditcabinet.documents.application.DocumentRecords
import ru.creditcabinet.documents.application.DocumentsOwnership
import ru.creditcabinet.documents.application.DocumentsStore
import ru.creditcabinet.documents.application.FrozenArtifactInput
import ru.creditcabinet.documents.application.GenerateArtifact
import ru.creditcabinet.documents.application.RenderArtifact
import ru.creditcabinet.documents.domain.ArtifactLifecycle
import java.time.Clock
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentsStoreTest {
    private val fixture = ArtifactFixture()
    private val bytes = "stored document".toByteArray()
    private val original = DocumentRecord(
        UUID.randomUUID(), fixture.scope, "OTHER", "document.pdf", "document.pdf", bytes.size.toLong(),
        UUID.randomUUID(), Instant.EPOCH, "BUSINESS", "S3", sha256(bytes), false,
    )
    private val files = ArtifactFiles().apply { this.bytes = this@DocumentsStoreTest.bytes }
    private val records = Records(original)
    private val store = store()

    @Test
    fun `deletion replay preserves timestamp and rejects another deletion without another write`() {
        val at = Instant.EPOCH.plusSeconds(100)
        store.delete(original.scope, original.id, "BUSINESS", at)
        store.delete(original.scope, original.id, "BUSINESS", at)
        assertEquals(original.copy(deletedAt = at), records.record)
        assertEquals(1, records.deletions)
        assertFailsWith<DocumentConflict> { store.delete(original.scope, original.id, "BUSINESS", at.plusSeconds(1)) }
        assertEquals(1, records.deletions)
    }

    @Test
    fun `read and deletion require the same scope and exact document context`() {
        assertContentEquals(bytes, store.read(original.scope, original.id, "BUSINESS"))
        for (scope in listOf(original.scope.copy(companyId = UUID.randomUUID()), original.scope.copy(applicationId = UUID.randomUUID()))) {
            assertFailsWith<NoSuchElementException> { store.read(scope, original.id, "BUSINESS") }
            assertFailsWith<NoSuchElementException> { store.delete(scope, original.id, "BUSINESS", Instant.EPOCH) }
        }
        assertFailsWith<NoSuchElementException> { store.read(original.scope, original.id, "CHAT") }
        assertFailsWith<NoSuchElementException> { store.delete(original.scope, original.id, "CHAT", Instant.EPOCH) }
        assertEquals(0, records.deletions)
        assertEquals(1, files.reads)
    }

    @Test
    fun `metadata listing retains tombstones and leaves business visibility with the Credit facade`() {
        store.delete(original.scope, original.id, "BUSINESS", Instant.EPOCH.plusSeconds(100))
        assertEquals(listOf(records.record), store.list(original.scope))
        assertContentEquals(bytes, store.read(original.scope, original.id, "BUSINESS"))
    }

    private fun store(): DocumentsStore {
        val policy = ArtifactLifecycle(2)
        val input = FrozenArtifactInput { fixture.input }
        val jobs = object : ArtifactJobs {
            override fun claim(policy: ArtifactLifecycle): ArtifactClaim? = null
            override fun publish(claim: ArtifactClaim, produceReady: () -> ArtifactRecord) = error("unused")
            override fun fail(claim: ArtifactClaim, retrySeconds: Long) = error("unused")
        }
        val metadata = object : DocumentMetadata {
            override fun find(id: UUID): DocumentRecord? = null
            override fun reserve(record: DocumentRecord) = error("unused")
            override fun insert(record: DocumentRecord) = error("unused")
        }
        val ownership = object : DocumentsOwnership {
            override fun <T> owned(epoch: Long, action: () -> T): T = action()
        }
        return DocumentsStore(
            records,
            StoreDocument(metadata, files, DocumentScanner {}),
            ArtifactOperations(records, ArtifactMemory(), input, files, policy),
            GenerateArtifact(jobs, input, RenderArtifact { _, _ -> error("unused") }, files, policy, Clock.systemUTC()),
            ownership,
            files,
        )
    }

    private class Records(var record: DocumentRecord) : DocumentRecords {
        var deletions = 0
        override fun verifyScope(scope: DocumentScope, bind: Boolean) = Unit
        override fun documents(scope: DocumentScope): List<DocumentRecord> = listOf(record).filter { it.scope == scope }
        override fun saveDeleted(record: DocumentRecord) {
            this.record = record
            deletions++
        }
    }
}
