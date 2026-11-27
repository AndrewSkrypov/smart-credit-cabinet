package ru.creditcabinet.documents

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.boot.CommandLineRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.generated.ArtifactInput
import ru.creditcabinet.document.infrastructure.LocalFileStore
import ru.creditcabinet.document.infrastructure.S3FileStore
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Offline admin-only migration. Both runtime writers must be stopped and fenced FROZEN.
 * Source credentials are read from environment, never command arguments or receipts.
 * The normal Documents runtime has no connection or credentials to Credit.
 */
@Component
@ConditionalOnProperty(name = ["cabinet.documents.migration.enabled"], havingValue = "true")
class DocumentsMigration(
    private val target: JdbcTemplate,
    private val tx: TransactionTemplate,
    private val mapper: ObjectMapper,
    private val files: FileStore,
    private val context: ConfigurableApplicationContext,
) : CommandLineRunner {
    override fun run(vararg args: String) {
        val direction = env("DOCUMENTS_MIGRATION_DIRECTION")
        require(direction in setOf("forward", "reverse"))
        val source =
            DriverManagerDataSource(env("DOCUMENTS_CREDIT_DATABASE_URL"), env("DOCUMENTS_CREDIT_DATABASE_USER"), env("DOCUMENTS_CREDIT_DATABASE_PASSWORD"))
        val creditLocal = LocalFileStore(env("DOCUMENTS_CREDIT_LOCAL_DIR"))
        val s3 =
            lazy {
                S3FileStore(
                    env("DOCUMENTS_CREDIT_S3_ENDPOINT"),
                    env("DOCUMENTS_CREDIT_S3_REGION"),
                    env("DOCUMENTS_CREDIT_S3_BUCKET"),
                    env("DOCUMENTS_CREDIT_S3_ACCESS_KEY"),
                    env("DOCUMENTS_CREDIT_S3_SECRET_KEY"),
                )
            }
        fun creditStore(backend: String): FileStore = if (backend == "LOCAL") creditLocal else s3.value
        source.connection.use { connection ->
            connection.autoCommit = false
            connection.transactionIsolation = java.sql.Connection.TRANSACTION_SERIALIZABLE
            try {
                val creditTx = JdbcTemplate(org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true)).apply { fetchSize = 100 }
                val gate = creditTx.queryForMap("select owner,epoch from document_ownership where singleton for update")
                check(gate["owner"] == "FROZEN")
                tx.executeWithoutResult {
                    val ownGate = target.queryForMap("select owner,epoch from document_ownership where singleton for update")
                    check(ownGate["owner"] == "FROZEN" && ownGate["epoch"] == gate["epoch"])
                    if (direction == "forward") forward(creditTx, ::creditStore) else reverse(creditTx, ::creditStore)
                }
                connection.commit()
            } catch (failure: Exception) {
                connection.rollback()
                // Fixed message excludes SQL parameters, object names and credentials.
                throw IllegalStateException("Documents migration failed; writers remain fenced", null)
            } finally {
                if (s3.isInitialized()) s3.value.close()
            }
        }
        context.close()
    }

    private fun forward(credit: JdbcTemplate, creditStore: (String) -> FileStore) {
        var documents = 0
        credit.query(
            "select d.*,a.company_id from documents d join applications a on a.id=d.application_id order by d.id",
            org.springframework.jdbc.core.RowCallbackHandler { rs ->
                val backend = rs.getString("storage_backend")
                val size = rs.getLong("size_bytes")
                val bytes = read(creditStore(backend), rs.getString("stored_name"), backend, size, rs.getString("checksum_sha256"))
                val record =
                    DocumentRecord(
                        uuid(
                            rs,
                            "id",
                        ),
                        DocumentScope(
                            uuid(rs, "company_id"),
                            uuid(rs, "application_id"),
                        ),
                        rs.getString("type"), rs.getString("file_name"), rs.getString("stored_name"), size,
                        uuid(
                            rs,
                            "uploaded_by",
                        ),
                        rs.getTimestamp(
                            "uploaded_at",
                        ).toInstant(),
                        rs.getString("context"), backend, sha256(bytes), rs.getBoolean("private_to_bank"), rs.getTimestamp("deleted_at")?.toInstant(),
                    )
                record.validate()
                bindScope(record.scope)
                val previous = target.query("select metadata from document_records where id=?", { row, _ ->
                    mapper.readValue<DocumentRecord>(row.getString(1))
                }, record.id).firstOrNull()
                check(previous == null || (previous.copy(deletedAt = record.deletedAt) == record && (previous.deletedAt == null || previous.deletedAt == record.deletedAt)))
                copy(files, record.storedName, backend, bytes)
                target.update(
                    "insert into document_records(id,company_id,application_id,context,metadata) values(?,?,?,?,?::jsonb) on conflict(id) do update set metadata=excluded.metadata",
                    record.id,
                    record.scope.companyId,
                    record.scope.applicationId,
                    record.context,
                    mapper.writeValueAsString(record),
                )
                documents++
            },
        )
        var artifacts = 0
        credit.query(
            "select g.*,a.company_id from generated_artifacts g join applications a on a.id=g.application_id order by g.created_at,g.id",
            org.springframework.jdbc.core.RowCallbackHandler { rs ->
                val input = mapper.readValue<ArtifactInput>(rs.getString("input_json"))
                val frozen = mapper.writeValueAsString(input)
                check(sha256(frozen.toByteArray()) == rs.getString("input_hash"))
                val record =
                    ArtifactRecord(
                        uuid(
                            rs,
                            "id",
                        ),
                        DocumentScope(
                            uuid(rs, "company_id"),
                            uuid(rs, "application_id"),
                        ),
                        uuid(rs, "offer_id"), rs.getString("kind"), rs.getString("phase"), rs.getInt("template_version"),
                        rs.getString(
                            "input_hash",
                        ),
                        frozen,
                        rs.getString(
                            "title",
                        ),
                        rs.getString(
                            "file_name",
                        ),
                        rs.getString(
                            "content_type",
                        ),
                        rs.getString("stored_name"), rs.getString("storage_backend"), rs.getTimestamp("created_at").toInstant(),
                        rs.getString(
                            "status",
                        ),
                        rs.getObject(
                            "size_bytes",
                            java.lang.Long::class.java,
                        )?.toLong(),
                        rs.getString("checksum"), rs.getTimestamp("ready_at")?.toInstant(),
                    )
                bindScope(record.scope)
                if (record.status ==
                    "READY"
                ) {
                    copy(
                        files,
                        record.storedName,
                        record.backend,
                        read(creditStore(record.backend), record.storedName, record.backend, record.sizeBytes!!, record.checksum),
                    )
                }
                val previous = target.query("select metadata from artifact_records where id=?", { row, _ ->
                    mapper.readValue<ArtifactRecord>(row.getString(1))
                }, record.id).firstOrNull()
                check(previous == null || previous == record)
                target.update(
                    "insert into artifact_records(id,company_id,application_id,offer_id,kind,phase,template_version,input_hash,metadata,status,attempts,next_attempt_at) values(?,?,?,?,?,?,?,?,?::jsonb,?,?,?) on conflict(id) do nothing",

                    record.id,
                    record.scope.companyId,
                    record.scope.applicationId,
                    record.offerId,
                    record.kind,
                    record.phase,
                    record.templateVersion,
                    record.inputHash,

                    mapper.writeValueAsString(
                        record,
                    ),
                    record.status, rs.getInt("attempts"), rs.getTimestamp("next_attempt_at"),
                )
                credit.update("insert into document_artifact_references(application_id,offer_id,phase,input_hash,input_json) values(?,?,?,?,?::jsonb) on conflict do nothing", record.scope.applicationId, record.offerId, record.phase, record.inputHash, frozen)
                // Import the immutable Credit snapshot as a retry intent, retaining old file links.
                credit.update(
                    "insert into document_artifact_inputs(application_id,offer_id,phase,input_hash,input_json,delivered_at) values(?,?,?,?,?::jsonb,now()) on conflict do nothing",

                    record.scope.applicationId,
                    record.offerId,
                    record.phase,
                    record.inputHash,
                    frozen,
                )
                artifacts++
            },
        )
        println("documents_migration direction=forward documents=$documents artifacts=$artifacts checksums=verified owner=FROZEN")
    }

    private fun reverse(credit: JdbcTemplate, creditStore: (String) -> FileStore) {
        // Requires explicit admin credentials. The application role must never own these tables.
        credit.execute("alter table documents disable trigger documents_owner_fence")
        credit.execute("alter table generated_artifacts disable trigger artifacts_owner_fence")
        target.query(
            "select metadata from document_records order by id",
            org.springframework.jdbc.core.RowCallbackHandler { rs ->
                var d = mapper.readValue<DocumentRecord>(rs.getString(1))
                // Expected orphan after Credit rollback/lost reply: never activate it on reverse.
                val committed = credit.query("select application_id from document_references where document_id=?", { row, _ ->
                    row.getObject(1, UUID::class.java)
                }, d.id).firstOrNull()
                if (committed == null) return@RowCallbackHandler
                check(committed == d.scope.applicationId)
                val deletion = credit.query("select deleted_at from document_deletion_intents where document_id=? and application_id=?", { row, _ -> row.getTimestamp(1).toInstant() }, d.id, d.scope.applicationId).firstOrNull()
                if (deletion != null) {
                    check(d.deletedAt == null || pgInstant(credit, d.deletedAt!!) == deletion)
                    d = d.copy(deletedAt = deletion)
                }
                check(credit.queryForObject("select company_id from applications where id=?", UUID::class.java, d.scope.applicationId) == d.scope.companyId)
                val previous = credit.query(
                    "select d.*,a.company_id from documents d join applications a on a.id=d.application_id where d.id=? for update of d",
                    {
                            row,
                            _,
                        ->
                        DocumentRecord(
                            uuid(
                                row,
                                "id",
                            ),
                            DocumentScope(
                                uuid(row, "company_id"),
                                uuid(row, "application_id"),
                            ),
                            row.getString(
                                "type",
                            ),
                            row.getString(
                                "file_name",
                            ),
                            row.getString(
                                "stored_name",
                            ),
                            row.getLong(
                                "size_bytes",
                            ),
                            uuid(row, "uploaded_by"), row.getTimestamp("uploaded_at").toInstant(), row.getString("context"), row.getString("storage_backend"),
                            row.getString("checksum_sha256") ?: d.checksum, row.getBoolean("private_to_bank"), row.getTimestamp("deleted_at")?.toInstant(),
                        )
                    },
                    d.id,
                ).firstOrNull()
                val expected = d.copy(uploadedAt = pgInstant(credit, d.uploadedAt), deletedAt = d.deletedAt?.let { pgInstant(credit, it) })
                if (previous != null) {
                    check(previous.copy(deletedAt = expected.deletedAt) == expected) { "Immutable document metadata conflict" }
                    check(previous.deletedAt == null || previous.deletedAt == expected.deletedAt)
                }
                copy(creditStore(d.backend), d.storedName, d.backend, read(files, d.storedName, d.backend, d.sizeBytes, d.checksum))
                credit.update(
                    """insert into documents(id,application_id,type,file_name,stored_name,size_bytes,uploaded_by,uploaded_at,context,storage_backend,checksum_sha256,private_to_bank,deleted_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict(id) do update set deleted_at=excluded.deleted_at,checksum_sha256=excluded.checksum_sha256""",
                    d.id, d.scope.applicationId, d.type, d.fileName, d.storedName, d.sizeBytes, d.uploadedBy,
                    Timestamp.from(
                        d.uploadedAt,
                    ),
                    d.context, d.backend, d.checksum, d.privateToBank, d.deletedAt?.let(Timestamp::from),
                )
            },
        )
        target.query(
            "select metadata,attempts,next_attempt_at from artifact_records order by id",
            org.springframework.jdbc.core.RowCallbackHandler { rs ->
                val a = mapper.readValue<ArtifactRecord>(rs.getString(1))
                val confirmed = credit.queryForObject("select exists(select 1 from document_artifact_references where application_id=? and offer_id=? and phase=? and input_hash=?)", Boolean::class.java, a.scope.applicationId, a.offerId, a.phase, a.inputHash)!!
                if (!confirmed) return@RowCallbackHandler
                check(credit.queryForObject("select company_id from applications where id=?", UUID::class.java, a.scope.applicationId) == a.scope.companyId)
                val previous = credit.query(
                    "select g.*,a.company_id from generated_artifacts g join applications a on a.id=g.application_id where g.id=? for update of g",
                    {
                            row,
                            _,
                        ->
                        ArtifactRecord(
                            uuid(
                                row,
                                "id",
                            ),
                            DocumentScope(
                                uuid(row, "company_id"),
                                uuid(row, "application_id"),
                            ),
                            uuid(
                                row,
                                "offer_id",
                            ),
                            row.getString(
                                "kind",
                            ),
                            row.getString(
                                "phase",
                            ),
                            row.getInt(
                                "template_version",
                            ),
                            row.getString(
                                "input_hash",
                            ),
                            mapper.writeValueAsString(
                                mapper.readValue<ArtifactInput>(row.getString("input_json")),
                            ),
                            row.getString(
                                "title",
                            ),
                            row.getString(
                                "file_name",
                            ),
                            row.getString(
                                "content_type",
                            ),
                            row.getString(
                                "stored_name",
                            ),
                            row.getString(
                                "storage_backend",
                            ),
                            row.getTimestamp(
                                "created_at",
                            ).toInstant(),
                            row.getString(
                                "status",
                            ),
                            row.getObject(
                                "size_bytes",
                                java.lang.Long::class.java,
                            )?.toLong(),
                            row.getString("checksum"), row.getTimestamp("ready_at")?.toInstant(),
                        )
                    },
                    a.id,
                ).firstOrNull()
                val expected = a.copy(createdAt = pgInstant(credit, a.createdAt), readyAt = a.readyAt?.let { pgInstant(credit, it) })
                if (previous != null) {
                    check(
                        previous.copy(
                            status = expected.status,
                            sizeBytes = expected.sizeBytes,
                            checksum = expected.checksum,
                            readyAt = expected.readyAt,
                        ) == expected,
                    ) {
                        "Immutable artifact metadata conflict"
                    }
                    if (previous.status == "READY") check(previous == expected)
                }
                if (a.status == "READY") copy(creditStore(a.backend), a.storedName, a.backend, read(files, a.storedName, a.backend, a.sizeBytes!!, a.checksum))
                credit.update(
                    """insert into generated_artifacts(id,application_id,offer_id,kind,phase,template_version,input_hash,input_json,title,file_name,content_type,stored_name,storage_backend,status,size_bytes,checksum,created_at,updated_at,ready_at,next_attempt_at,attempts)
                values(?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?::jsonb,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    now(),
                    ?,
                    ?,?) on conflict(id) do update set status=excluded.status,
                    size_bytes=excluded.size_bytes,
                    checksum=excluded.checksum,
                    ready_at=excluded.ready_at,next_attempt_at=excluded.next_attempt_at,attempts=excluded.attempts""",
                    a.id,
                    a.scope.applicationId,
                    a.offerId,
                    a.kind,
                    a.phase,
                    a.templateVersion,
                    a.inputHash,
                    a.inputJson,
                    a.title,
                    a.fileName,
                    a.contentType,
                    a.storedName,
                    a.backend,
                    a.status,
                    a.sizeBytes,
                    a.checksum,

                    Timestamp.from(
                        a.createdAt,
                    ),
                    a.readyAt?.let(Timestamp::from),
                    rs.getTimestamp("next_attempt_at"),
                    rs.getInt("attempts"),
                )
            },
        )
        credit.execute("alter table documents enable trigger documents_owner_fence")
        credit.execute("alter table generated_artifacts enable trigger artifacts_owner_fence")
        println("documents_migration direction=reverse checksums=verified owner=FROZEN")
    }

    // Compare file metadata at PostgreSQL's actual timestamptz precision. Frozen
    // ArtifactInput JSON and its hash are compared verbatim and never normalized here.
    private fun pgInstant(
        credit: JdbcTemplate,
        instant: Instant,
    ): Instant = credit.queryForObject("select cast(? as timestamptz)", { rs, _ -> rs.getTimestamp(1).toInstant() }, Timestamp.from(instant))!!

    private fun bindScope(scope: DocumentScope) {
        target.update(
            "insert into document_application_scopes(application_id,company_id) values(?,?) on conflict do nothing",
            scope.applicationId,
            scope.companyId,
        )
        check(
            target.queryForObject("select company_id from document_application_scopes where application_id=?", UUID::class.java, scope.applicationId) ==
                scope.companyId,
        )
    }
    private fun read(store: FileStore, name: String, backend: String, size: Long, checksum: String?): ByteArray {
        require(size in 1..MAX_DOCUMENT_BYTES)
        val file = store.get(name, StorageBackend.valueOf(backend)) ?: error("Missing bytes")
        val bytes = file.content.use { it.readNBytes(MAX_DOCUMENT_BYTES + 1) }
        check(file.sizeBytes == size && bytes.size.toLong() == size && (checksum == null || sha256(bytes) == checksum))
        return bytes
    }
    private fun copy(store: FileStore, name: String, backend: String, bytes: ByteArray) {
        val old = store.get(name, StorageBackend.valueOf(backend))
        if (old != null) {
            val previous = old.content.use { it.readNBytes(MAX_DOCUMENT_BYTES + 1) }
            check(previous.contentEquals(bytes))
            return
        }
        store.put(name, bytes.inputStream(), bytes.size.toLong(), StorageBackend.valueOf(backend))
        check(read(store, name, backend, bytes.size.toLong(), sha256(bytes)).contentEquals(bytes))
    }
    private fun env(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("Missing migration configuration: $name")
    private fun uuid(rs: ResultSet, name: String): UUID = rs.getObject(name, UUID::class.java)
}
