package ru.creditcabinet.chat.infrastructure

import org.springframework.stereotype.Component
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.chat.domain.ChatAttachments
import ru.creditcabinet.chat.domain.ChatFile
import ru.creditcabinet.chat.domain.ChatUpload
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.document.application.DocumentService
import java.util.UUID

@Component
class ChatDocuments(private val service: DocumentService) : ChatAttachments {
    override fun find(application: LoanApplication, documentId: UUID, caller: TokenClaims): ChatFile {
        val document = service.chatAttachment(application, documentId, caller)
        return ChatFile(document.id, document.fileName, document.sizeBytes)
    }

    override fun upload(application: LoanApplication, caller: TokenClaims, upload: ChatUpload): ChatFile {
        val document = service.uploadChat(application, caller, upload)
        return ChatFile(document.id, document.fileName, document.sizeBytes)
    }
}
