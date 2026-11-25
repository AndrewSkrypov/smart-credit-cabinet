package ru.creditcabinet.notification.domain

import java.time.Instant
import java.util.UUID

enum class NotificationType {
    /** The application moved on; nothing is required. */
    APPLICATION_STATUS,

    /** The client has to act: add documents, answer an offer, sign. */
    ACTION_REQUIRED,

    /** Bank: an application waits for a manual decision. */
    NEW_REVIEW,

    /** A participant wrote in the application's conversation. */
    APPLICATION_MESSAGE,
}

data class Notification(
    val id: UUID = UUID.randomUUID(),
    val recipientId: UUID,
    val type: NotificationType,
    val title: String,
    val body: String,
    val link: String?,
    val dedupKey: String?,
    val readAt: Instant? = null,
    val createdAt: Instant,
) {
    companion object {
        /** A path inside the cabinet: "/client/applications/…", never "//evil.example" or "https://…". */
        val INTERNAL_LINK = Regex("^/(?!/)[A-Za-z0-9/_\\-.~%?=&]{0,510}$")
    }
}
