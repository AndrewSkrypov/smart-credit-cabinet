package ru.creditcabinet.notification

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.CurrentCaller
import java.time.Clock
import java.util.UUID

/** Who a notification is for: a person, everyone of a company, or everyone with a bank role. Exactly one is set. */
data class Audience(val userId: UUID? = null, val companyId: UUID? = null, val role: String? = null) {
    init {
        require(listOfNotNull(userId, companyId, role).size == 1) { "Укажите одного адресата: пользователя, компанию или роль" }
    }
}

data class NewNotification(
    val audience: Audience,
    val type: NotificationType,
    val title: String,
    val body: String,
    val link: String?,
    val dedupKey: String?,
)

data class NotificationPage(val items: List<NotificationView>, val page: Int, val size: Int, val total: Int, val unread: Int)

data class NotificationView(
    val id: UUID,
    val type: NotificationType,
    val title: String,
    val body: String,
    val link: String?,
    val read: Boolean,
    val createdAt: java.time.Instant,
) {
    companion object {
        fun of(notification: Notification) =
            NotificationView(
                notification.id,
                notification.type,
                notification.title,
                notification.body,
                notification.link,
                notification.readAt != null,
                notification.createdAt,
            )
    }
}

@Service
class NotificationService(
    private val store: NotificationStore,
    private val people: PeopleDirectory,
    private val clock: Clock,
) {
    /** Turns each address into people and stores one row per person; a repeated delivery is stored once. */
    @Transactional
    fun deliver(items: List<NewNotification>): Int {
        val now = clock.instant()
        val rows = items.flatMap { item ->
            require(item.link == null || Notification.INTERNAL_LINK.matches(item.link)) { "Ссылка уведомления должна вести внутрь кабинета" }
            val recipients = when {
                item.audience.userId != null -> listOf(item.audience.userId)
                item.audience.companyId != null -> people.ofCompany(item.audience.companyId)
                else -> people.withRole(item.audience.role!!)
            }
            recipients.distinct().map { recipient ->
                Notification(
                    recipientId = recipient,
                    type = item.type,
                    title = item.title.trim(),
                    body = item.body.trim(),
                    link = item.link,
                    dedupKey = item.dedupKey,
                    createdAt = now,
                )
            }
        }
        return store.insertNew(rows)
    }

    fun page(page: Int, size: Int): NotificationPage {
        val me = CurrentCaller.claims().userId
        val index = page.coerceAtLeast(0)
        val limit = size.coerceIn(1, MAX_PAGE)
        return NotificationPage(store.page(me, index * limit, limit).map(NotificationView::of), index, limit, store.total(me), store.unread(me))
    }

    fun unread(): Int = store.unread(CurrentCaller.claims().userId)

    /** Another person's notification is "not found", like any id one cannot see. */
    fun markRead(id: UUID) {
        val me = CurrentCaller.claims().userId
        val notification = store.find(id)?.takeIf { it.recipientId == me } ?: throw NotFoundException("Уведомление не найдено")
        store.markRead(notification.id, me, clock.instant())
    }

    fun markAllRead(): Int = store.markAllRead(CurrentCaller.claims().userId, clock.instant())

    private companion object {
        const val MAX_PAGE = 50
    }
}
