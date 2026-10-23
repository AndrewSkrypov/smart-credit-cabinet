package ru.creditcabinet.chat.infrastructure

import org.springframework.stereotype.Component
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.chat.domain.ChatAttachments
import ru.creditcabinet.chat.domain.ChatFile
import ru.creditcabinet.chat.domain.ChatUpload
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.document.DocumentService
import ru.creditcabinet.document.domain.DocumentContext
import ru.creditcabinet.document.domain.DocumentRepository
import java.util.UUID

@Component
class ChatDocuments(private val documents: DocumentRepository, private val service: DocumentService) : ChatAttachments {
    override fun find(applicationId: UUID, documentId: UUID, callerId: UUID): ChatFile {
        val document = documents.find(applicationId, documentId) ?: throw NotFoundException("Документ не найден")
        if (document.context == DocumentContext.CHAT && document.uploadedBy != callerId && !documents.isChatAttached(documentId)) {
            throw NotFoundException("Документ не найден")
        }
        return ChatFile(document.id, document.fileName, document.sizeBytes)
    }

    override fun upload(application: LoanApplication, caller: TokenClaims, upload: ChatUpload): ChatFile {
        val document = service.uploadChat(application, caller, upload)
        return ChatFile(document.id, document.fileName, document.sizeBytes)
    }
}
