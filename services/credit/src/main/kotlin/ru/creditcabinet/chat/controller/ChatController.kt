package ru.creditcabinet.chat.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import ru.creditcabinet.chat.application.ChatService
import ru.creditcabinet.chat.domain.ChatAudit
import ru.creditcabinet.chat.domain.ChatFile
import ru.creditcabinet.chat.domain.ChatMessage
import ru.creditcabinet.chat.domain.ChatPage
import ru.creditcabinet.chat.domain.ChatSide
import ru.creditcabinet.chat.domain.ChatState
import ru.creditcabinet.chat.domain.ChatUpload
import ru.creditcabinet.common.exception.BadRequestException
import java.time.Instant
import java.util.UUID

data class MessageRequest(val text: String = "", val documentIds: List<UUID> = emptyList())
data class ReadRequest(val lastReadSeq: Long)
data class MessageView(
    val id: UUID,
    val seq: Long,
    val authorName: String,
    val side: ChatSide,
    val text: String,
    val createdAt: Instant,
    val attachments: List<ChatFile>,
) {
    companion object {
        fun of(message: ChatMessage) = with(message) { MessageView(id, seq, authorName, side, text, createdAt, attachments) }
    }
}

data class ChatView(
    val messages: List<MessageView>,
    val lastSeq: Long,
    val readSeq: Long,
    val unreadCount: Long,
    val side: ChatSide,
    val writable: Boolean,
    val readOnlyReason: String?,
    val hasOlder: Boolean,
    val hasNewer: Boolean,
) {
    companion object {
        fun of(
            page: ChatPage,
        ) = with(page) { ChatView(messages.map(MessageView::of), lastSeq, readSeq, unreadCount, side, writable, readOnlyReason, hasOlder, hasNewer) }
    }
}

@Tag(name = "Переписка по заявке")
@RestController
@RequestMapping("/api/v1/applications/{id}/messages")
class ChatController(private val chat: ChatService) {
    @Operation(summary = "Сообщения участников заявки; без курсора — последние, before — история, after — новые")
    @GetMapping
    fun list(
        @PathVariable id: UUID,
        @RequestParam(required = false) after: Long?,
        @RequestParam(required = false) before: Long?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ChatView = ChatView.of(chat.page(id, after, before, limit))

    @Operation(summary = "Отправить простой текст до 2000 символов или вложения; повтор ключа не создаёт новое сообщение")
    @PostMapping
    fun send(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestBody request: MessageRequest): MessageView =
        MessageView.of(chat.send(id, key(key), request.text, request.documentIds))

    @Operation(summary = "Отметить прочитанным для стороны компании или банка, не уменьшая курсор")
    @PostMapping("/read")
    fun read(@PathVariable id: UUID, @RequestBody request: ReadRequest): ChatState = chat.read(id, request.lastReadSeq)

    @Operation(summary = "Загрузить вложение через хранилище документов, не меняя комплектность или журнал заявки")
    @PostMapping("/attachments", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(@PathVariable id: UUID, @RequestHeader("Idempotency-Key") key: String, @RequestPart file: MultipartFile): ChatFile =
        chat.upload(id, key(key), ChatUpload(file.originalFilename ?: "document", file.bytes))

    @Operation(summary = "Отдельный аудит переписки для сотрудников банка; бизнес-журнал заявки не меняется")
    @GetMapping("/audit")
    fun audit(@PathVariable id: UUID, @RequestParam(defaultValue = "100") limit: Int): List<ChatAudit> = chat.audit(id, limit)

    private fun key(value: String): UUID = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw BadRequestException("Укажите UUID в заголовке Idempotency-Key")
    }
}
