package ru.creditcabinet.document

import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.document.domain.ApplicationDocument
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.domain.StoredFile
import ru.creditcabinet.document.infrastructure.DocumentContentAdapter
import ru.creditcabinet.document.infrastructure.DocumentsBoundary
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DocumentContentTest {
    private val files = mock<FileStore>()
    private val boundary = mock<DocumentsBoundary>()
    private val content = DocumentContentAdapter(files, boundary)
    private val document = ApplicationDocument(
        applicationId = UUID.randomUUID(),
        type = DocumentType.OTHER,
        fileName = "invoice.pdf",
        storedName = "invoice.pdf",
        sizeBytes = 3,
        uploadedBy = UUID.randomUUID(),
        uploadedAt = Instant.EPOCH,
        storageBackend = StorageBackend.S3,
    )

    @Test
    fun `remote invoice reader uses authoritative bytes and never a stale local object`() {
        val remote = StoredFile(3, byteArrayOf(1, 2, 3).inputStream())
        whenever(boundary.remote).thenReturn(true)
        whenever(boundary.bytes(document)).thenReturn(remote)
        assertSame(remote, content.open(document))
        verifyNoInteractions(files)
    }

    @Test
    fun `remote invoice outage fails closed without consulting local bytes`() {
        whenever(boundary.remote).thenReturn(true)
        whenever(boundary.bytes(document)).thenThrow(ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Документы недоступны"))
        assertFailsWith<ApiException> { content.open(document) }
        verifyNoInteractions(files)
    }

    @Test
    fun `local reader keeps the backend recorded on the document`() {
        val local = StoredFile(3, byteArrayOf(1, 2, 3).inputStream())
        whenever(boundary.remote).thenReturn(false)
        whenever(files.get(document.storedName, StorageBackend.S3)).thenReturn(local)
        assertSame(local, content.open(document))
        verify(files).get(document.storedName, StorageBackend.S3)
    }
}
