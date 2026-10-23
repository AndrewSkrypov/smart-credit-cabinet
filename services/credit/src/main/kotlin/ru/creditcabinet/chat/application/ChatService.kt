package ru.creditcabinet.chat.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.service.ApplicationService
import ru.creditcabinet.chat.domain.ChatAction
import ru.creditcabinet.chat.domain.ChatAttachments
import ru.creditcabinet.chat.domain.ChatAudit
import ru.creditcabinet.chat.domain.ChatData
import ru.creditcabinet.chat.domain.ChatFile
import ru.creditcabinet.chat.domain.ChatLimits
import ru.creditcabinet.chat.domain.ChatMessage
import ru.creditcabinet.chat.domain.ChatPage
import ru.creditcabinet.chat.domain.ChatRules
import ru.creditcabinet.chat.domain.ChatSide
import ru.creditcabinet.chat.domain.ChatState
import ru.creditcabinet.chat.domain.ChatUpload
import ru.creditcabinet.chat.domain.UploadReceipt
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.ForbiddenException
import ru.creditcabinet.common.notifications.PlatformNotification
import ru.creditcabinet.common.notifications.PlatformNotifications
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import java.time.Clock
import java.util.UUID

@Service
class ChatService(
    private val applications: ApplicationService,
    private val data: ChatData,
    private val files: ChatAttachments,
    private val limits: ChatLimits,
    private val notifications: PlatformNotifications,
    private val clock: Clock,
) {
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun page(id: UUID, after: Long?, before: Long?, limit: Int): ChatPage {
        val application = applications.get(id)
        require(limit in 1..100) { "За один запрос можно получить от 1 до 100 сообщений" }
        require(after == null || after >= 0) { "Номер последнего сообщения не может быть отрицательным" }
        require(before == null || before > 0) { "Номер сообщения для истории должен быть больше нуля" }
        require(after == null || before == null) { "Выберите один способ загрузки: новые сообщения или историю" }
        val side = side(CurrentCaller.claims())
        val state = data.state(id, side)
        val messages = data.messages(id, after, before, limit)
        val reason = ChatRules.readOnlyReason(application.status)
        return ChatPage(
            messages, state.lastSeq, state.readSeq, state.unreadCount, side, reason == null, reason,
            messages.firstOrNull()?.seq?.let { it > 1 } ?: false,
            messages.lastOrNull()?.seq?.let { it < state.lastSeq } ?: false,
        )
    }

    @Transactional
    fun send(id: UUID, key: UUID, text: String, documents: List<UUID>): ChatMessage {
        val application = applications.get(id)
        val caller = CurrentCaller.claims()
        val status = data.lockApplication(id)
        val seq = data.lockState(id)
        ChatRules.validate(text, documents)
        val hash = ChatRules.messageHash(text, documents)
        data.previousMessage(id, caller.userId, key)?.let { previous ->
            if (previous.payloadHash != hash) throw ConflictException("Этот ключ уже использован для другого сообщения")
            return previous
        }
        ChatRules.requireWritable(status)
        limits.acquire(caller.userId)
        val attachments = documents.map { files.find(id, it, caller.userId) }
        val now = clock.instant()
        val message = ChatMessage(
            applicationId = id, seq = seq + 1, authorId = caller.userId,
            authorName = (caller.fullName ?: if (caller.isClient) "Представитель компании" else "Сотрудник банка").take(255),
            authorRole = role(caller), side = side(caller), text = text, key = key, payloadHash = hash, createdAt = now, attachments = attachments,
        )
        data.append(message)
        data.audit(
            ChatAudit(
                applicationId = id,
                actorId = caller.userId,
                actorRole = role(caller),
                action = ChatAction.MESSAGE_SENT,
                messageId = message.id,
                occurredAt = now,
            ),
        )
        notifyOtherParty(application, message)
        return message
    }

    @Transactional
    fun read(id: UUID, seq: Long): ChatState {
        applications.get(id)
        val caller = CurrentCaller.claims()
        val side = side(caller)
        val lastSeq = data.lockState(id)
        require(seq in 0..lastSeq) { "Нельзя отметить прочитанным сообщение, которого нет в заявке" }
        val previous = data.state(id, side)
        if (seq > previous.readSeq) {
            val now = clock.instant()
            data.cursor(id, side, seq, caller.userId, now)
            data.audit(
                ChatAudit(
                    applicationId = id,
                    actorId = caller.userId,
                    actorRole = role(caller),
                    action = ChatAction.READ_CURSOR_ADVANCED,
                    readSeq = seq,
                    occurredAt = now,
                ),
            )
        }
        return data.state(id, side)
    }

    @Transactional
    fun upload(id: UUID, key: UUID, upload: ChatUpload): ChatFile {
        val application = applications.get(id)
        val caller = CurrentCaller.claims()
        val status = data.lockApplication(id)
        data.lockState(id)
        val hash = ChatRules.uploadHash(upload)
        data.previousUpload(id, caller.userId, key)?.let { previous ->
            if (previous.hash != hash) throw ConflictException("Этот ключ уже использован для другого файла")
            return files.find(id, previous.documentId, caller.userId)
        }
        ChatRules.requireWritable(status)
        val stored = files.upload(application, caller, upload)
        val now = clock.instant()
        data.recordUpload(id, caller.userId, key, UploadReceipt(hash, stored.id), now)
        data.audit(
            ChatAudit(
                applicationId = id,
                actorId = caller.userId,
                actorRole = role(caller),
                action = ChatAction.ATTACHMENT_UPLOADED,
                documentId = stored.id,
                occurredAt = now,
            ),
        )
        return stored
    }

    fun unread(applications: List<LoanApplication>): Map<UUID, Long> =
        data.unread(applications.map { it.id }, side(CurrentCaller.claims()))

    fun audit(id: UUID, limit: Int): List<ChatAudit> {
        CurrentCaller.requireBankStaff()
        applications.get(id)
        require(limit in 1..100) { "За один запрос можно получить от 1 до 100 записей аудита" }
        return data.audit(id, limit)
    }

    private fun notifyOtherParty(application: LoanApplication, message: ChatMessage) {
        val title = "Новое сообщение по заявке № ${application.number}"
        val body = "Откройте переписку в кабинете, чтобы прочитать сообщение."
        val key = "chat:${message.id}"
        val notices = if (message.side == ChatSide.COMPANY) {
            listOf(Roles.MANAGER, Roles.UNDERWRITER, Roles.RISK_ADMIN).map { role ->
                PlatformNotification(
                    recipientRole = role,
                    type = "APPLICATION_MESSAGE",
                    title = title,
                    body = body,
                    link = "/bank/applications/${application.id}",
                    dedupKey = "$key:$role",
                )
            }
        } else {
            listOf(
                PlatformNotification(
                    recipientCompanyId = application.company.id,
                    type = "APPLICATION_MESSAGE",
                    title = title,
                    body = body,
                    link = "/client/applications/${application.id}",
                    dedupKey = "$key:company",
                ),
            )
        }
        notifications.send(notices)
    }

    private fun role(caller: TokenClaims): String = caller.role ?: throw ForbiddenException(CurrentCaller.FORBIDDEN)

    private fun side(caller: TokenClaims): ChatSide = when {
        caller.isClient -> ChatSide.COMPANY
        caller.isBankStaff -> ChatSide.BANK
        else -> throw ForbiddenException(CurrentCaller.FORBIDDEN)
    }
}
