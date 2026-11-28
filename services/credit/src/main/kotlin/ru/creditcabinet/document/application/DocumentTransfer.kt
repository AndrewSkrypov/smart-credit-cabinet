package ru.creditcabinet.document.application

import ru.creditcabinet.document.domain.ApplicationDocument
import ru.creditcabinet.document.domain.DocumentContext
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.domain.StoredFile
import java.time.Instant
import java.util.UUID

data class DocumentUploadIntent(
    val companyId: UUID,
    val applicationId: UUID,
    val actor: UUID,
    val context: DocumentContext,
    val type: DocumentType,
    val fileName: String,
    val size: Long,
    val privateToBank: Boolean,
    val bytesHash: String,
)

data class UploadOperation(val at: Instant, val backend: StorageBackend)

interface DocumentTransfer {
    val remote: Boolean

    fun reserveUpload(id: UUID, intent: DocumentUploadIntent, backend: StorageBackend, at: Instant): UploadOperation

    fun lockUpload(id: UUID)

    fun committedUpload(applicationId: UUID, id: UUID): ApplicationDocument?

    fun put(document: ApplicationDocument, bytes: ByteArray): ApplicationDocument

    fun commitReference(document: ApplicationDocument): Boolean

    fun bytes(document: ApplicationDocument): StoredFile
}
