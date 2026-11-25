package ru.creditcabinet.notification.application

import ru.creditcabinet.common.notifications.NotificationEmail
import ru.creditcabinet.notification.domain.Notification
import java.time.Instant
import java.util.UUID

interface NotificationStore {
    /** Stores those not stored yet (by recipient and dedup key) and returns how many went in. */
    fun insertNew(notifications: List<Notification>): Int
    fun page(recipientId: UUID, offset: Int, limit: Int): List<Notification>
    fun total(recipientId: UUID): Int
    fun unread(recipientId: UUID): Int
    fun find(id: UUID): Notification?
    fun markRead(id: UUID, recipientId: UUID, at: Instant)
    fun markAllRead(recipientId: UUID, at: Instant): Int
}

/** The people behind an address: everyone of a company, or everyone with a bank role. */
interface PeopleDirectory {
    fun ofCompany(companyId: UUID): List<UUID>
    fun withRole(role: String): List<UUID>
}

interface NotificationReceipts {
    fun claim(item: NewNotification): Boolean
}

fun interface NotificationEmailPort {
    fun enqueue(notification: Notification, content: NotificationEmail?)
}

interface NotificationUseCases {
    fun deliver(items: List<NewNotification>): Int
    fun page(page: Int, size: Int): NotificationPage
    fun unread(): Int
    fun markRead(id: UUID)
    fun markAllRead(): Int
}
