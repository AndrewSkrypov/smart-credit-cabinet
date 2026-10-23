package ru.creditcabinet.chat.domain

import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.common.security.TokenClaims
import java.time.Instant
import java.util.UUID

interface ChatData {
    /** Shares the application's status lock with transitions without changing its version or journal. */
    fun lockApplication(applicationId: UUID): ApplicationStatus
    fun lockState(applicationId: UUID): Long
    fun state(applicationId: UUID, side: ChatSide): ChatState
    fun messages(applicationId: UUID, after: Long?, before: Long?, limit: Int): List<ChatMessage>
    fun previousMessage(applicationId: UUID, actorId: UUID, key: UUID): ChatMessage?
    fun append(message: ChatMessage)
    fun cursor(applicationId: UUID, side: ChatSide, seq: Long, actorId: UUID, now: Instant)
    fun unread(applicationIds: List<UUID>, side: ChatSide): Map<UUID, Long>
    fun previousUpload(applicationId: UUID, actorId: UUID, key: UUID): UploadReceipt?
    fun recordUpload(applicationId: UUID, actorId: UUID, key: UUID, receipt: UploadReceipt, now: Instant)
    fun audit(entry: ChatAudit)
    fun audit(applicationId: UUID, limit: Int): List<ChatAudit>
}

interface ChatAttachments {
    fun find(applicationId: UUID, documentId: UUID, callerId: UUID): ChatFile
    fun upload(application: LoanApplication, caller: TokenClaims, upload: ChatUpload): ChatFile
}

fun interface ChatLimits {
    fun acquire(actorId: UUID)
}
