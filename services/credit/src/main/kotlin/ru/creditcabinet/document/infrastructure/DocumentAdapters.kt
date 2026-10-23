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
        )
    }

    override fun list(applicationId: UUID): List<ApplicationDocument> =
        jdbc.query(
            "select * from documents where application_id = :application and context = 'BUSINESS' and deleted_at is null order by uploaded_at",
            mapOf("application" to applicationId),
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
            insert into documents (id, application_id, type, file_name, stored_name, size_bytes, uploaded_by, uploaded_at, context, storage_backend, checksum_sha256)
            values (:id, :application, :type, :fileName, :storedName, :size, :uploadedBy, :uploadedAt, :context, :backend, :checksum)
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

/** Files in a local directory (a docker volume). Names are random and never come from a request. */
@Component
class LocalFileStore(@Value("\${cabinet.storage.local-dir:./uploads}") directory: String) : FileStore {
    private val root: Path = Path.of(directory).toAbsolutePath().normalize()

    init {
        Files.createDirectories(root)
    }

    override fun put(storedName: String, content: InputStream, sizeBytes: Long) {
        val target = resolve(storedName)
        val temporary = Files.createTempFile(root, ".upload-", ".tmp")
        try {
            Files.copy(content, temporary, StandardCopyOption.REPLACE_EXISTING)
            if (Files.size(temporary) != sizeBytes) throw java.io.IOException("Incomplete document write")
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    override fun get(storedName: String): StoredFile? {
        val path = resolve(storedName)
        if (!Files.isRegularFile(path)) return null
        return StoredFile(Files.size(path), Files.newInputStream(path))
    }

    private fun resolve(name: String): Path {
        val path = root.resolve(name).normalize()
        require(path.parent == root) { "Invalid stored name" }
        return path
    }
}
