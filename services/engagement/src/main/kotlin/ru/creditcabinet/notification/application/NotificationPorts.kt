package ru.creditcabinet.notification.application

import ru.creditcabinet.common.notifications.NotificationEmail
import ru.creditcabinet.notification.domain.Notification
import java.time.Instant
import java.util.UUID

interface NotificationStore {
    /** Stores those not stored yet (by recipient and dedup key) and returns how many went in. */
    fun insertNew(notifications: List<Notification>): Int
    fun agentApplicationLinks(recipientId: UUID, unreadOnly: Boolean): List<String>
    fun page(recipientId: UUID, offset: Long, limit: Int, accessibleAgentLinks: Set<String>): List<Notification>
    fun total(recipientId: UUID, accessibleAgentLinks: Set<String>): Int
    fun unread(recipientId: UUID, accessibleAgentLinks: Set<String>): Int
    fun find(id: UUID): Notification?
    fun markRead(id: UUID, recipientId: UUID, at: Instant)
    fun markAllRead(recipientId: UUID, at: Instant, accessibleAgentLinks: Set<String>): Int
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

enum class AgentNotificationAccess { ALLOWED, DENIED, UNAVAILABLE }

fun interface AgentNotificationAccessPort {
    fun check(applicationId: UUID, authorization: String): AgentNotificationAccess
}

interface NotificationUseCases {
    fun deliver(items: List<NewNotification>): Int
    fun page(page: Int, size: Int, authorization: String?): NotificationPage
    fun unread(authorization: String?): Int
    fun markRead(id: UUID, authorization: String?)
    fun markAllRead(authorization: String?): Int
}
