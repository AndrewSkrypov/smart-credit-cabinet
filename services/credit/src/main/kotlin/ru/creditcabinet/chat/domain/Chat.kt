package ru.creditcabinet.chat.domain

import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.common.exception.ConflictException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

enum class ChatSide { COMPANY, BANK }

data class ChatFile(val id: UUID, val fileName: String, val sizeBytes: Long)

data class ChatMessage(
    val id: UUID = UUID.randomUUID(),
    val applicationId: UUID,
    val seq: Long,
    val authorId: UUID,
    val authorName: String,
    val authorRole: String,
    val side: ChatSide,
    val text: String,
    val key: UUID,
    val payloadHash: String,
    val createdAt: Instant,
    val attachments: List<ChatFile> = emptyList(),
)

enum class ChatAction { MESSAGE_SENT, ATTACHMENT_UPLOADED, READ_CURSOR_ADVANCED }

data class ChatAudit(
    val id: UUID = UUID.randomUUID(),
    val applicationId: UUID,
    val actorId: UUID,
    val actorRole: String,
    val action: ChatAction,
    val messageId: UUID? = null,
    val documentId: UUID? = null,
    val readSeq: Long? = null,
    val occurredAt: Instant,
)

data class ChatState(val lastSeq: Long, val readSeq: Long, val unreadCount: Long)

data class ChatPage(
    val messages: List<ChatMessage>,
    val lastSeq: Long,
    val readSeq: Long,
    val unreadCount: Long,
    val side: ChatSide,
    val writable: Boolean,
    val readOnlyReason: String?,
    val hasOlder: Boolean,
    val hasNewer: Boolean,
)

data class ChatUpload(val fileName: String, val bytes: ByteArray)

data class UploadReceipt(val hash: String, val documentId: UUID)

object ChatRules {
    const val MAX_TEXT = 2000
    const val MAX_FILES = 5

    fun readOnlyReason(status: ApplicationStatus): String? = when {
        status == ApplicationStatus.DRAFT -> "Переписка доступна после отправки заявки в банк"
        status.terminal -> "Заявка завершена. Переписка доступна только для чтения"
        else -> null
    }

    fun requireWritable(status: ApplicationStatus) {
        readOnlyReason(status)?.let { throw ConflictException(it) }
    }

    fun validate(text: String, documents: List<UUID>) {
        require(text.length <= MAX_TEXT) { "Сообщение — до 2000 символов" }
        require('\u0000' !in text) { "Удалите нулевой символ из сообщения" }
        require(documents.size <= MAX_FILES) { "К сообщению можно приложить до пяти файлов" }
        require(documents.size == documents.distinct().size) { "Не повторяйте одно вложение в сообщении" }
        require(text.isNotBlank() || documents.isNotEmpty()) { "Введите сообщение или приложите файл" }
    }

    fun messageHash(text: String, documents: List<UUID>): String =
        hash((text + "\u0000" + documents.sorted().joinToString(",")).toByteArray(Charsets.UTF_8))

    fun uploadHash(upload: ChatUpload): String = hash(upload.fileName.toByteArray(Charsets.UTF_8) + byteArrayOf(0) + upload.bytes)

    private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
