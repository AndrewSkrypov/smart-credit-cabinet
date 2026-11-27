package ru.creditcabinet.documents

import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import ru.creditcabinet.document.domain.DocumentScanner
import ru.creditcabinet.document.domain.FileStore
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class DocumentsUploadReplayTest {
    @Test
    fun `lost upload reply replay preserves original metadata and rejects changed payload or deleted identity`() {
        val bytes = "%PDF-1.4\nreplay\n%%EOF".toByteArray()
        val id = UUID.randomUUID()
        val first =
            DocumentRecord(
                id,
                DocumentScope(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                ),
                "OTHER", "proof.pdf", "$id.pdf", bytes.size.toLong(), UUID.randomUUID(),
                Instant.parse(
                    "2026-01-01T00:00:00Z",
                ),
                "CHAT", "LOCAL", sha256(bytes), false,
            )
        val metadata = mock<DocumentMetadata>()
        val files = mock<FileStore>()
        val scanner = mock<DocumentScanner>()
        whenever(metadata.find(id)).thenReturn(first)
        val useCase = StoreDocument(metadata, files, scanner)
        useCase.execute(first.copy(uploadedAt = first.uploadedAt.plusSeconds(100), backend = "S3"), bytes)
        verify(metadata, never()).insert(any())
        verify(files, never()).put(any(), any(), any(), any())
        verify(scanner, never()).scan(any())
        assertFailsWith<DocumentConflict> { useCase.execute(first.copy(fileName = "different.pdf"), bytes) }
        assertFailsWith<DocumentConflict> { useCase.execute(first.copy(context = "BUSINESS"), bytes) }
        whenever(metadata.find(id)).thenReturn(first.copy(deletedAt = first.uploadedAt.plusSeconds(10)))
        assertFailsWith<DocumentConflict> { useCase.execute(first, bytes) }
    }
}
