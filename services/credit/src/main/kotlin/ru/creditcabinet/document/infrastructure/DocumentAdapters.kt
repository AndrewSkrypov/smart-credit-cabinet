package ru.creditcabinet.document.infrastructure

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import ru.creditcabinet.document.domain.ApplicationDocument
import ru.creditcabinet.document.domain.DocumentContext
import ru.creditcabinet.document.domain.DocumentRepository
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.domain.StoredFile
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Component
class JdbcDocumentRepository(private val jdbc: NamedParameterJdbcTemplate) : DocumentRepository {
    private val mapper = RowMapper { rs, _ ->
        ApplicationDocument(
            id = rs.getObject("id", UUID::class.java),
            applicationId = rs.getObject("application_id", UUID::class.java),
            type = DocumentType.valueOf(rs.getString("type")),
            fileName = rs.getString("file_name"),
            storedName = rs.getString("stored_name"),
            sizeBytes = rs.getLong("size_bytes"),
            uploadedBy = rs.getObject("uploaded_by", UUID::class.java),
            uploadedAt = rs.getTimestamp("uploaded_at").toInstant(),
            context = DocumentContext.valueOf(rs.getString("context")),
            storageBackend = StorageBackend.valueOf(rs.getString("storage_backend")),
            checksumSha256 = rs.getString("checksum_sha256"),
            privateToBank = rs.getBoolean("private_to_bank"),
        )
    }

    override fun list(applicationId: UUID): List<ApplicationDocument> =
        jdbc.query(
            "select * from documents where application_id = :application and context = 'BUSINESS' and deleted_at is null order by uploaded_at",
            mapOf("application" to applicationId),
            mapper,
        )

    override fun listForPacket(applicationId: UUID, submittedAt: Instant?, documentId: UUID?): List<ApplicationDocument> =
        jdbc.query(
            """
            with latest_packet as (
                select document_ids
                from financing_scenario_publications
                where application_id = :application and status = 'AWAITING_CLIENT'
                order by revision desc
                limit 1
            )
            select d.*
            from documents d left join latest_packet p on true
            where d.application_id = :application and d.context = 'BUSINESS'
              and (cast(:document as uuid) is null or d.id = :document)
              and (
                (d.deleted_at is null and (p.document_ids is null or d.uploaded_at >= cast(:submittedAt as timestamptz)))
                or jsonb_exists(p.document_ids, d.id::text)
              )
            order by d.uploaded_at
            """.trimIndent(),
            mapOf("application" to applicationId, "submittedAt" to submittedAt?.let(Timestamp::from), "document" to documentId),
            mapper,
        )

    override fun find(applicationId: UUID, documentId: UUID): ApplicationDocument? =
        jdbc.query(
            "select * from documents where id = :id and application_id = :application and deleted_at is null",
            mapOf("id" to documentId, "application" to applicationId),
            mapper,
        ).firstOrNull()

    override fun save(document: ApplicationDocument) {
        jdbc.update(
            """
            insert into documents (
                id, application_id, type, file_name, stored_name, size_bytes, uploaded_by, uploaded_at, context, storage_backend, checksum_sha256,
                private_to_bank
            ) values (:id, :application, :type, :fileName, :storedName, :size, :uploadedBy, :uploadedAt, :context, :backend, :checksum, :privateToBank)
            """.trimIndent(),
            mapOf(
                "id" to document.id,
                "application" to document.applicationId,
                "type" to document.type.name,
                "fileName" to document.fileName,
                "storedName" to document.storedName,
                "size" to document.sizeBytes,
                "uploadedBy" to document.uploadedBy,
                "uploadedAt" to Timestamp.from(document.uploadedAt),
                "context" to document.context.name,
                "backend" to document.storageBackend.name,
                "checksum" to document.checksumSha256,
                "privateToBank" to document.privateToBank,
            ),
        )
    }

    // The row stays for the journal and the audit; the file is no longer offered.
    override fun markDeleted(documentId: UUID, at: Instant) {
        jdbc.update("update documents set deleted_at = :at where id = :id", mapOf("id" to documentId, "at" to Timestamp.from(at)))
    }

    override fun isChatAttached(documentId: UUID): Boolean = jdbc.queryForObject(
        "select exists(select 1 from message_attachments where document_id=:id)",
        mapOf("id" to documentId),
        Boolean::class.java,
    )!!
}
