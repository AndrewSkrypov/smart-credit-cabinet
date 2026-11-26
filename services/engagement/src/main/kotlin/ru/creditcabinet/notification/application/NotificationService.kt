package ru.creditcabinet.notification.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.notifications.NotificationEmail
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.notification.domain.Notification
import ru.creditcabinet.notification.domain.NotificationType
import ru.creditcabinet.notification.domain.agentApplicationId
import ru.creditcabinet.notification.domain.isAgentApplicationLink
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
    val dedupKey: String,
    val email: NotificationEmail? = null,
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
    private val receipts: NotificationReceipts,
    private val emailDelivery: NotificationEmailPort,
    private val agentAccess: AgentNotificationAccessPort,
) : NotificationUseCases {
    /** Turns each address into people and stores one row per person; a repeated delivery is stored once. */
    @Transactional
    override fun deliver(items: List<NewNotification>): Int {
        val now = clock.instant()
        return items.sumOf { item ->
            require(item.link == null || Notification.INTERNAL_LINK.matches(item.link)) { "Ссылка уведомления должна вести внутрь кабинета" }
            if (!receipts.claim(item)) return@sumOf 0
            val recipients = when {
                item.audience.userId != null -> listOf(item.audience.userId)
                item.audience.companyId != null -> people.ofCompany(item.audience.companyId)
                else -> people.withRole(item.audience.role!!)
            }
            val rows = recipients.distinct().map { recipient ->
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
            val inserted = store.insertNew(rows)
            rows.forEach { row -> emailDelivery.enqueue(row, item.email) }
            inserted
        }
    }

    override fun page(page: Int, size: Int, authorization: String?): NotificationPage {
        val caller = CurrentCaller.claims()
        val links = accessibleAgentLinks(caller, authorization, unreadOnly = false)
        val index = page.coerceAtLeast(0)
        val limit = size.coerceIn(1, MAX_PAGE)
        return NotificationPage(
            store.page(caller.userId, index.toLong() * limit, limit, links).map(NotificationView::of),
            index,
            limit,
            store.total(caller.userId, links),
            store.unread(caller.userId, links),
        )
    }

    override fun unread(authorization: String?): Int {
        val caller = CurrentCaller.claims()
        return store.unread(caller.userId, accessibleAgentLinks(caller, authorization, unreadOnly = true))
    }

    /** An inaccessible notification is indistinguishable from an unknown ID. */
    override fun markRead(id: UUID, authorization: String?) {
        val caller = CurrentCaller.claims()
        val notification = store.find(id)?.takeIf { it.recipientId == caller.userId } ?: notFound()
        if (notification.link.isAgentApplicationLink()) {
            val application = notification.link?.agentApplicationId() ?: notFound()
            if (!caller.isAgent || authorization == null ||
                agentAccess.check(application, authorization) != AgentNotificationAccess.ALLOWED
            ) notFound()
        }
        store.markRead(notification.id, caller.userId, clock.instant())
    }

    override fun markAllRead(authorization: String?): Int {
        val caller = CurrentCaller.claims()
        val links = accessibleAgentLinks(caller, authorization, unreadOnly = true)
        return store.markAllRead(caller.userId, clock.instant(), links)
    }

    private fun accessibleAgentLinks(caller: TokenClaims, authorization: String?, unreadOnly: Boolean): Set<String> {
        if (!caller.isAgent || authorization == null) return emptySet()
        val checked = mutableMapOf<UUID, AgentNotificationAccess>()
        val allowed = mutableSetOf<String>()
        for (link in store.agentApplicationLinks(caller.userId, unreadOnly)) {
            val application = link.agentApplicationId() ?: continue
            when (checked.getOrPut(application) { agentAccess.check(application, authorization) }) {
                AgentNotificationAccess.ALLOWED -> allowed.add(link)
                AgentNotificationAccess.DENIED -> Unit
                AgentNotificationAccess.UNAVAILABLE -> return emptySet()
            }
        }
        return allowed
    }

    private fun notFound(): Nothing = throw NotFoundException("Уведомление не найдено")

    private companion object {
        const val MAX_PAGE = 50
    }
}
