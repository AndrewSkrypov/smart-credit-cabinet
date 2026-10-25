package ru.creditcabinet.notification

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import ru.creditcabinet.common.mail.MailSettings
import ru.creditcabinet.common.mail.MailTransport
import ru.creditcabinet.common.mail.OutgoingMail
import ru.creditcabinet.common.notifications.NotificationEmail
import ru.creditcabinet.common.notifications.NotificationEmailKind
import ru.creditcabinet.common.outbox.DeliveryHandler
import ru.creditcabinet.common.outbox.DurableOutbox
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.HexFormat
import java.util.UUID

data class NotificationMailJob(val recipientId: UUID, val content: NotificationEmail, val link: String, val key: String)

@Component
class NotificationEmailDelivery(
    private val preferences: NotificationPreferences,
    private val outbox: DurableOutbox,
    private val mapper: ObjectMapper,
) {
    fun enqueue(notification: Notification, content: NotificationEmail?) {
        if (content == null || notification.link == null || !preferences.get(notification.recipientId).emailEnabled) return
        val key = eventKey(notification.dedupKey!!, notification.recipientId)
        val job = NotificationMailJob(notification.recipientId, content, notification.link, key)
        outbox.enqueue("email-notification", key, mapper.writeValueAsString(job))
    }

    companion object {
        fun eventKey(event: String, recipient: UUID): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest("$event:$recipient".toByteArray(Charsets.UTF_8)))
    }
}

interface MailRecipientDirectory {
    fun email(userId: UUID): String?
}

@Component
class IdentityMailRecipientDirectory(@Value("\${cabinet.identity.url:}") identityUrl: String, builder: RestClient.Builder) : MailRecipientDirectory {
    private val client = builder.clone().baseUrl(identityUrl).requestFactory(
        SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(2))
            setReadTimeout(Duration.ofSeconds(5))
        },
    ).build()

    private data class Contact(val email: String, val status: String)

    override fun email(userId: UUID): String? {
        val contact = client.get().uri("/internal/v1/users/{id}", userId).retrieve().body(Contact::class.java)!!
        return contact.email.takeIf { contact.status == "ACTIVE" }
    }
}

@Component
class NotificationMailHandler(
    private val mapper: ObjectMapper,
    private val settings: MailSettings,
    private val preferences: NotificationPreferences,
    private val recipients: MailRecipientDirectory,
    private val transport: MailTransport,
) : DeliveryHandler {
    override val channel = "email-notification"

    override fun deliver(payload: String) {
        val job = mapper.readValue(payload, NotificationMailJob::class.java)
        require(job.link.matches(Regex("^/(client|bank)/applications/[0-9a-fA-F-]{36}$"))) { "Notification mail requires an application link" }
        if (!preferences.get(job.recipientId).emailEnabled) return
        val recipient = recipients.email(job.recipientId) ?: return
        val template = NotificationMailTemplates.render(job.content, settings.cabinetLink(job.link))
        transport.send(OutgoingMail(recipient, template.first, template.second, job.key))
    }
}

object NotificationMailTemplates {
    fun render(content: NotificationEmail, link: String): Pair<String, String> {
        val subject = when (content.kind) {
            NotificationEmailKind.DOCUMENTS_REQUESTED -> "Банк просит дополнить заявку"
            NotificationEmailKind.OFFER_READY -> "В кабинете доступно предложение банка"
            NotificationEmailKind.SIGNING_READY -> "Договор готов к подписанию"
            NotificationEmailKind.DISBURSED -> "Кредит выдан"
            NotificationEmailKind.REFUSED -> "По заявке принято решение"
            NotificationEmailKind.REVIEW_REQUIRED -> "Новая заявка ждёт решения"
            NotificationEmailKind.FILES_READY -> "Документы по кредиту готовы"
        }
        val deadline = content.deadline?.let {
            val formatted = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Europe/Moscow")).format(Instant.parse(it))
            "\nСрок ответа: $formatted (Москва)."
        } ?: ""
        val details = when (content.kind) {
            NotificationEmailKind.DISBURSED -> "Файлы по кредиту появятся после подготовки. Об их готовности сообщим отдельно."
            NotificationEmailKind.FILES_READY -> "В кабинете доступны PDF условий и график платежей в PDF и XLSX. Файлы открываются после входа в кабинет."
            else -> "Подробности доступны в кабинете."
        }
        return subject to "$subject.$deadline\n$details Откройте кабинет:\n$link"
    }
}
