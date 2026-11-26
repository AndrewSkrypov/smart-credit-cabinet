package ru.creditcabinet.document

import org.springframework.stereotype.Component
import org.springframework.web.multipart.MultipartFile
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.chat.domain.ChatUpload
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.document.application.DocumentApplication
import ru.creditcabinet.document.application.DocumentUpload
import ru.creditcabinet.document.controller.toDocumentUpload
import ru.creditcabinet.document.domain.DocumentType
import java.util.UUID

/** Adapts the agent and chat call sites to the documents use cases. */
@Component
class DocumentService(private val documents: DocumentApplication) {
    fun uploadForAgent(application: LoanApplication, claims: TokenClaims, type: DocumentType, file: MultipartFile) =
        documents.uploadForAgent(application, claims, type, file.toDocumentUpload())

    fun deleteForAgent(application: LoanApplication, claims: TokenClaims, documentId: UUID) =
        documents.deleteForAgent(application, claims, documentId)

    fun open(applicationId: UUID, documentId: UUID) = documents.open(applicationId, documentId)

    fun uploadChat(application: LoanApplication, caller: TokenClaims, upload: ChatUpload) =
        documents.uploadChat(application, caller, DocumentUpload(upload.fileName, upload.bytes.size.toLong()) { upload.bytes })
}
