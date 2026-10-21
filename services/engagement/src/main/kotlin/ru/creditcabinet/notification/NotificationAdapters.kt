package ru.creditcabinet.notification

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Component
class JdbcNotificationStore(private val jdbc: NamedParameterJdbcTemplate) : NotificationStore {
    override fun insertNew(notifications: List<Notification>): Int =
        notifications.count { item ->
            // "returning" answers only when the row went in: a repeated delivery is skipped.
            jdbc.query(
                """
                insert into notifications (id, recipient_id, type, title, body, link, dedup_key, created_at)
                values (:id, :recipient, :type, :title, :body, :link, :dedup, :created)
                on conflict (recipient_id, dedup_key) where dedup_key is not null do nothing
                returning id
                """.trimIndent(),
                mapOf(
                    "id" to item.id,
                    "recipient" to item.recipientId,
                    "type" to item.type.name,
                    "title" to item.title,
                    "body" to item.body,
                    "link" to item.link,
                    "dedup" to item.dedupKey,
                    "created" to Timestamp.from(item.createdAt),
                ),
            ) { rs, _ -> rs.getObject(1, UUID::class.java) }.isNotEmpty()
        }

    override fun page(recipientId: UUID, offset: Int, limit: Int): List<Notification> =
        jdbc.query(
            "select * from notifications where recipient_id = :r order by created_at desc, id offset :offset limit :limit",
            mapOf("r" to recipientId, "offset" to offset, "limit" to limit),
            mapper,
        )

    override fun total(recipientId: UUID): Int =
        jdbc.queryForObject("select count(*) from notifications where recipient_id = :r", mapOf("r" to recipientId), Int::class.java) ?: 0

    override fun unread(recipientId: UUID): Int =
        jdbc.queryForObject("select count(*) from notifications where recipient_id = :r and read_at is null", mapOf("r" to recipientId), Int::class.java) ?: 0

    override fun find(id: UUID): Notification? = jdbc.query("select * from notifications where id = :id", mapOf("id" to id), mapper).firstOrNull()

    override fun markRead(id: UUID, recipientId: UUID, at: Instant) {
        jdbc.update(
            "update notifications set read_at = :at where id = :id and recipient_id = :r and read_at is null",
            mapOf("id" to id, "r" to recipientId, "at" to Timestamp.from(at)),
        )
    }

    override fun markAllRead(recipientId: UUID, at: Instant): Int =
        jdbc.update("update notifications set read_at = :at where recipient_id = :r and read_at is null", mapOf("r" to recipientId, "at" to Timestamp.from(at)))

    private val mapper = RowMapper { rs, _ ->
        Notification(
            id = rs.getObject("id", UUID::class.java),
            recipientId = rs.getObject("recipient_id", UUID::class.java),
            type = NotificationType.valueOf(rs.getString("type")),
            title = rs.getString("title"),
            body = rs.getString("body"),
            link = rs.getString("link"),
            dedupKey = rs.getString("dedup_key"),
            readAt = rs.getTimestamp("read_at")?.toInstant(),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }
}

/** identity's internal user lookups. Short timeouts: delivery runs in the background and can be retried. */
@Component
class IdentityPeopleDirectory(
    @Value("\${cabinet.identity.url:}") identityUrl: String,
    builder: RestClient.Builder,
) : PeopleDirectory {
    private val client: RestClient? = identityUrl.takeIf { it.isNotBlank() }?.let {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(2))
            setReadTimeout(Duration.ofSeconds(5))
        }
        builder.clone().baseUrl(it).requestFactory(requestFactory).build()
    }

    private data class Person(val id: UUID)

    override fun ofCompany(companyId: UUID): List<UUID> = query("/internal/v1/users?companyId={value}", companyId)

    override fun withRole(role: String): List<UUID> = query("/internal/v1/users?role={value}", role)

    private fun query(uri: String, value: Any): List<UUID> {
        val client = client ?: error("cabinet.identity.url is not set")
        return client.get().uri(uri, value).retrieve().body(Array<Person>::class.java).orEmpty().map { it.id }
    }
}
