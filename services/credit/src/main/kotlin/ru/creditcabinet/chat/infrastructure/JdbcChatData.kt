package ru.creditcabinet.chat.infrastructure

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.chat.domain.ChatAction
import ru.creditcabinet.chat.domain.ChatAudit
import ru.creditcabinet.chat.domain.ChatData
import ru.creditcabinet.chat.domain.ChatFile
import ru.creditcabinet.chat.domain.ChatMessage
import ru.creditcabinet.chat.domain.ChatSide
import ru.creditcabinet.chat.domain.ChatState
import ru.creditcabinet.chat.domain.UploadReceipt
import ru.creditcabinet.common.exception.NotFoundException
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Component
class JdbcChatData(private val jdbc: NamedParameterJdbcTemplate) : ChatData {
    private val mapper = RowMapper { rs, _ ->
        ChatMessage(
            id = rs.getObject("id", UUID::class.java), applicationId = rs.getObject("application_id", UUID::class.java),
            seq = rs.getLong("seq"), authorId = rs.getObject("author_id", UUID::class.java), authorName = rs.getString("author_name"),
            authorRole = rs.getString("author_role"), side = ChatSide.valueOf(rs.getString("side")), text = rs.getString("message_text"),
            key = rs.getObject(
                "idempotency_key",
                UUID::class.java,
            ),
            payloadHash = rs.getString("payload_hash"), createdAt = rs.getTimestamp("created_at").toInstant(),
        )
    }

    override fun lockApplication(applicationId: UUID): ApplicationStatus = jdbc.query(
        "select status from applications where id = :id for share",
        mapOf("id" to applicationId),
    ) { rs, _ -> ApplicationStatus.valueOf(rs.getString("status")) }.firstOrNull() ?: throw NotFoundException("Заявка не найдена")

    override fun lockState(applicationId: UUID): Long {
        jdbc.update("insert into application_chat_state(application_id) values (:id) on conflict do nothing", mapOf("id" to applicationId))
        return jdbc.queryForObject(
            "select last_seq from application_chat_state where application_id = :id for update",
            mapOf("id" to applicationId),
            Long::class.java,
        )!!
    }

    override fun state(applicationId: UUID, side: ChatSide): ChatState = jdbc.query(
        """
        select coalesce(s.last_seq,0) last_seq, coalesce(c.last_read_seq,0) read_seq,
            (select count(*) from application_messages m where m.application_id=a.id and m.side<>:side and m.seq>coalesce(c.last_read_seq,0)) unread
        from applications a left join application_chat_state s on s.application_id=a.id
        left join application_chat_read_cursors c on c.application_id=a.id and c.side=:side where a.id=:id
        """.trimIndent(),
        mapOf("id" to applicationId, "side" to side.name),
    ) { rs, _ -> ChatState(rs.getLong("last_seq"), rs.getLong("read_seq"), rs.getLong("unread")) }.single()

    override fun messages(applicationId: UUID, after: Long?, before: Long?, limit: Int): List<ChatMessage> {
        val condition = if (after != null) {
            "and seq > :after"
        } else if (before != null) {
            "and seq < :before"
        } else {
            ""
        }
        val order = if (after != null) "asc" else "desc"
        val rows = jdbc.query(
            "select * from application_messages where application_id=:id $condition order by seq $order limit :limit",
            mapOf("id" to applicationId, "after" to after, "before" to before, "limit" to limit),
            mapper,
        )
        return withFiles(if (after != null) rows else rows.reversed())
    }

    override fun previousMessage(applicationId: UUID, actorId: UUID, key: UUID): ChatMessage? = withFiles(
        jdbc.query(
            "select * from application_messages where application_id=:app and author_id=:actor and idempotency_key=:key",
            mapOf("app" to applicationId, "actor" to actorId, "key" to key),
            mapper,
        ),
    ).firstOrNull()

    override fun append(message: ChatMessage) {
        jdbc.update(
            """
            insert into application_messages(id, application_id, seq, author_id, author_name, author_role, side, message_text, idempotency_key,
                payload_hash, created_at) values (:id,:app,:seq,:actor,:name,:role,:side,:text,:key,:hash,:at)
            """.trimIndent(),
            mapOf(
                "id" to message.id, "app" to message.applicationId, "seq" to message.seq, "actor" to message.authorId,
                "name" to message.authorName, "role" to message.authorRole, "side" to message.side.name, "text" to message.text,
                "key" to message.key, "hash" to message.payloadHash, "at" to Timestamp.from(message.createdAt),
            ),
        )
        message.attachments.forEach { file ->
            jdbc.update(
                "insert into message_attachments(message_id,application_id,document_id) values (:message,:app,:document)",
                mapOf("message" to message.id, "app" to message.applicationId, "document" to file.id),
            )
        }
        val changed = jdbc.update(
            "update application_chat_state set last_seq=:seq where application_id=:app and last_seq=:previous",
            mapOf("seq" to message.seq, "app" to message.applicationId, "previous" to message.seq - 1),
        )
        check(changed == 1) { "Chat sequence must be held by its transaction" }
    }

    override fun cursor(applicationId: UUID, side: ChatSide, seq: Long, actorId: UUID, now: Instant) {
        jdbc.update(
            """
            insert into application_chat_read_cursors(application_id,side,last_read_seq,actor_id,updated_at) values (:app,:side,:seq,:actor,:at)
            on conflict (application_id,side) do update set last_read_seq=greatest(application_chat_read_cursors.last_read_seq,excluded.last_read_seq),
                actor_id=excluded.actor_id,updated_at=excluded.updated_at
            """.trimIndent(),
            mapOf("app" to applicationId, "side" to side.name, "seq" to seq, "actor" to actorId, "at" to Timestamp.from(now)),
        )
    }

    override fun unread(applicationIds: List<UUID>, side: ChatSide): Map<UUID, Long> {
        if (applicationIds.isEmpty()) return emptyMap()
        return jdbc.query(
            """
            select m.application_id, count(*) unread from application_messages m
            left join application_chat_read_cursors c on c.application_id=m.application_id and c.side=:side
            where m.application_id in (:ids) and m.side<>:side and m.seq>coalesce(c.last_read_seq,0) group by m.application_id
            """.trimIndent(),
            mapOf("ids" to applicationIds, "side" to side.name),
        ) { rs, _ -> rs.getObject("application_id", UUID::class.java) to rs.getLong("unread") }.toMap()
    }

    override fun previousUpload(applicationId: UUID, actorId: UUID, key: UUID): UploadReceipt? = jdbc.query(
        "select payload_hash,document_id from application_chat_upload_keys where application_id=:app and actor_id=:actor and idempotency_key=:key",
        mapOf("app" to applicationId, "actor" to actorId, "key" to key),
    ) { rs, _ -> UploadReceipt(rs.getString("payload_hash"), rs.getObject("document_id", UUID::class.java)) }.firstOrNull()

    override fun recordUpload(applicationId: UUID, actorId: UUID, key: UUID, receipt: UploadReceipt, now: Instant) {
        jdbc.update(
            """
            insert into application_chat_upload_keys(application_id,actor_id,idempotency_key,payload_hash,document_id,created_at)
            values (:app,:actor,:key,:hash,:document,:at)
            """.trimIndent(),
            mapOf(
                "app" to applicationId,
                "actor" to actorId,
                "key" to key,
                "hash" to receipt.hash,
                "document" to receipt.documentId,
                "at" to Timestamp.from(now),
            ),
        )
    }

    override fun audit(entry: ChatAudit) {
        jdbc.update(
            """
            insert into application_chat_audit(id,application_id,actor_id,actor_role,action,message_id,document_id,read_seq,occurred_at)
            values (:id,:app,:actor,:role,:action,:message,:document,:seq,:at)
            """.trimIndent(),
            mapOf(
                "id" to entry.id, "app" to entry.applicationId, "actor" to entry.actorId, "role" to entry.actorRole,
                "action" to entry.action.name, "message" to entry.messageId, "document" to entry.documentId, "seq" to entry.readSeq,
                "at" to Timestamp.from(entry.occurredAt),
            ),
        )
    }

    override fun audit(applicationId: UUID, limit: Int): List<ChatAudit> = jdbc.query(
        "select * from application_chat_audit where application_id=:app order by occurred_at desc,id desc limit :limit",
        mapOf("app" to applicationId, "limit" to limit),
    ) { rs, _ ->
        ChatAudit(
            id = rs.getObject("id", UUID::class.java), applicationId = applicationId,
            actorId = rs.getObject("actor_id", UUID::class.java), actorRole = rs.getString("actor_role"), action = ChatAction.valueOf(rs.getString("action")),
            messageId = rs.getObject("message_id", UUID::class.java), documentId = rs.getObject("document_id", UUID::class.java),
            readSeq = rs.getObject("read_seq", java.lang.Long::class.java)?.toLong(), occurredAt = rs.getTimestamp("occurred_at").toInstant(),
        )
    }

    private fun withFiles(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.isEmpty()) return messages
        val attachments = jdbc.query(
            """
            select a.message_id,d.id,d.file_name,d.size_bytes from message_attachments a join documents d on d.id=a.document_id
            where a.message_id in (:ids) order by d.uploaded_at,d.id
            """.trimIndent(),
            mapOf("ids" to messages.map { it.id }),
        ) { rs, _ ->
            rs.getObject("message_id", UUID::class.java) to
                ChatFile(rs.getObject("id", UUID::class.java), rs.getString("file_name"), rs.getLong("size_bytes"))
        }
            .groupBy({ it.first }, { it.second })
        return messages.map { it.copy(attachments = attachments[it.id].orEmpty()) }
    }
}
