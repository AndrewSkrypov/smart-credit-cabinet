package ru.creditcabinet.document.application

import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.document.domain.ApplicationDocument
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.document.domain.StoredFile
import java.util.UUID

data class DocumentUpload(val fileName: String?, val size: Long, val read: () -> ByteArray)

interface ApplicationDocuments {
    fun list(applicationId: UUID): List<ApplicationDocument>

    fun list(authorizedApplication: LoanApplication): List<ApplicationDocument>

    fun upload(applicationId: UUID, type: DocumentType, file: DocumentUpload): ApplicationDocument

    fun delete(applicationId: UUID, documentId: UUID)

    fun open(applicationId: UUID, documentId: UUID): Pair<ApplicationDocument, StoredFile>
}

fun interface DocumentUploadLimit {
    fun acquire(userId: UUID)
}
