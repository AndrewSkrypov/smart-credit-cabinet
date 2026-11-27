package ru.creditcabinet.documents

import ru.creditcabinet.document.domain.DocumentScanner
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StoredFile
import java.io.InputStream
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class StoreDocumentTest {
    private val bytes = "%PDF-1.4\nprivate statement\n%%EOF".toByteArray()
    private val record = DocumentRecord(
        UUID.randomUUID(), DocumentScope(UUID.randomUUID(), UUID.randomUUID()),
        "BANK_STATEMENT", "statement.pdf", "statement.pdf", bytes.size.toLong(), UUID.randomUUID(),
        Instant.parse("2026-11-27T00:00:00Z"), "BUSINESS", "LOCAL", sha256(bytes), true,
    )

    @Test
    fun `new upload scans before writing bytes and publishes metadata after object storage`() {
        val metadata = MemoryMetadata()
        val files = MemoryFiles()
        var scanned = false
        files.beforeWrite = { assertEquals(true, scanned) }
        metadata.beforeInsert = { assertContentEquals(bytes, files.bytes) }

        StoreDocument(metadata, files, DocumentScanner { scanned = true }).execute(record, bytes)

        assertEquals(record, metadata.record)
        assertEquals(1, files.writes)
        assertEquals(1, metadata.inserts)
    }

    @Test
    fun `failed scan or object write never publishes document metadata`() {
        val metadata = MemoryMetadata()
        val files = MemoryFiles()
        val failedScanner = DocumentScanner { throw IllegalStateException("scan unavailable") }
        assertFailsWith<IllegalStateException> { StoreDocument(metadata, files, failedScanner).execute(record, bytes) }
        assertEquals(0, files.writes)
        assertNull(metadata.record)

        files.beforeWrite = { throw IllegalStateException("object store unavailable") }
        assertFailsWith<IllegalStateException> { StoreDocument(metadata, files, DocumentScanner {}).execute(record, bytes) }
        assertNull(metadata.record)
        assertEquals(0, metadata.inserts)
    }

    @Test
    fun `matching orphan is adopted without overwriting bytes`() {
        val metadata = MemoryMetadata()
        val files = MemoryFiles(bytes.copyOf())
        StoreDocument(metadata, files, DocumentScanner {}).execute(record, bytes)
        assertEquals(0, files.writes)
        assertEquals(record, metadata.record)
        assertContentEquals(bytes, files.bytes)
    }

    @Test
    fun `different orphan bytes cannot be overwritten or published`() {
        val metadata = MemoryMetadata()
        val previous = bytes.copyOf().also { it[0] = '!'.code.toByte() }
        val files = MemoryFiles(previous)
        assertFailsWith<IllegalArgumentException> { StoreDocument(metadata, files, DocumentScanner {}).execute(record, bytes) }
        assertEquals(0, files.writes)
        assertContentEquals(previous, files.bytes)
        assertNull(metadata.record)
    }

    @Test
    fun `replay preserves committed identity and rejects another payload or deleted identity`() {
        val metadata = MemoryMetadata(record)
        val files = MemoryFiles(bytes.copyOf())
        val useCase = StoreDocument(metadata, files, DocumentScanner { error("replay must not scan") })
        useCase.execute(record.copy(uploadedAt = record.uploadedAt.plusSeconds(1), backend = "S3"), bytes)
        assertEquals(record, metadata.record)
        assertEquals(0, metadata.inserts)
        assertEquals(0, files.writes)
        assertFailsWith<DocumentConflict> { useCase.execute(record.copy(fileName = "other.pdf"), bytes) }
        metadata.record = record.copy(deletedAt = record.uploadedAt.plusSeconds(1))
        assertFailsWith<DocumentConflict> { useCase.execute(record, bytes) }
    }

    @Test
    fun `unsafe names privacy scope and altered bytes fail before reserving an upload`() {
        val metadata = MemoryMetadata()
        val files = MemoryFiles()
        val useCase = StoreDocument(metadata, files, DocumentScanner {})
        val invalidRecords = listOf(
            record.copy(storedName = "../statement.pdf"),
            record.copy(fileName = "statement\u0000.pdf"),
            record.copy(type = "OTHER"),
            record.copy(context = "CHAT"),
            record.copy(checksum = "0".repeat(64)),
        )
        for (invalid in invalidRecords) {
            assertFailsWith<IllegalArgumentException> { useCase.execute(invalid, bytes) }
        }
        assertEquals(0, metadata.reservations)
        assertEquals(0, files.writes)
    }

    private class MemoryMetadata(var record: DocumentRecord? = null) : DocumentMetadata {
        var reservations = 0
        var inserts = 0
        var beforeInsert: () -> Unit = {}

        override fun find(id: UUID): DocumentRecord? = record?.takeIf { it.id == id }

        override fun reserve(record: DocumentRecord) {
            reservations++
        }

        override fun insert(record: DocumentRecord) {
            beforeInsert()
            this.record = record
            inserts++
        }
    }

    private class MemoryFiles(var bytes: ByteArray? = null) : FileStore {
        var writes = 0
        var beforeWrite: () -> Unit = {}

        override fun put(storedName: String, content: InputStream, sizeBytes: Long) {
            beforeWrite()
            bytes = content.readBytes()
            writes++
        }

        override fun get(storedName: String): StoredFile? = bytes?.let { StoredFile(it.size.toLong(), it.inputStream()) }
    }
}
