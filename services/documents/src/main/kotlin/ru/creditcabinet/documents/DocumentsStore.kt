package ru.creditcabinet.documents

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.document.domain.DocumentScanner
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.generated.ArtifactInput
import ru.creditcabinet.document.generated.ArtifactKind
import ru.creditcabinet.document.generated.ArtifactRenderer
import java.time.Instant
import java.util.UUID

/** Database adapter. A scoped lock fences every operation against ownership changes. */
@Component
class DocumentsStore(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val tx: TransactionTemplate,
    private val files: FileStore,
    private val scanner: DocumentScanner,
    private val renderer: ArtifactRenderer,
    private val metadata: JdbcDocumentMetadata,
) {
    fun <T> owned(epoch: Long, block: () -> T): T = tx.execute {
        val gate = jdbc.queryForMap("select owner,epoch from document_ownership where singleton for share")
        check(gate["owner"] == "DOCUMENTS" && (gate["epoch"] as Number).toLong() == epoch) { "Ownership fenced" }
        block()
    }!!

    private fun verifyScope(scope: DocumentScope, bind: Boolean = false) {
        if (bind) {
            jdbc.update(
                "insert into document_application_scopes(application_id,company_id) values(?,?) on conflict do nothing",
                scope.applicationId,
                scope.companyId,
            )
        }
        val company = jdbc.query("select company_id from document_application_scopes where application_id=?", { rs, _ ->
            rs.getObject(1, UUID::class.java)
        }, scope.applicationId).firstOrNull()
        require(company == null || company == scope.companyId)
    }
    fun list(scope: DocumentScope): List<DocumentRecord> {
        verifyScope(scope)
        return jdbc.query(
            "select metadata from document_records where company_id=? and application_id=? order by created_at,id",
            { rs, _ -> mapper.readValue<DocumentRecord>(rs.getString(1)) },
            scope.companyId,
            scope.applicationId,
        )
    }

    fun put(record: DocumentRecord, bytes: ByteArray) {
        verifyScope(record.scope, bind = true)
        StoreDocument(metadata, files, scanner).execute(record, bytes)
    }

    private fun document(scope: DocumentScope, id: UUID, context: String): DocumentRecord =
        list(scope).firstOrNull { it.id == id && it.context == context } ?: throw NoSuchElementException()

    fun read(scope: DocumentScope, id: UUID, context: String): ByteArray {
        val record = document(scope, id, context)
        return bytes(record.storedName, record.backend, record.sizeBytes, record.checksum)
    }

    fun delete(scope: DocumentScope, id: UUID, context: String, at: Instant) {
        val record = document(scope, id, context)
        require(record.deletedAt == null)
        jdbc.update("update document_records set metadata=?::jsonb where id=?", mapper.writeValueAsString(record.copy(deletedAt = at)), id)
    }

    fun artifacts(scope: DocumentScope): List<ArtifactRecord> {
        verifyScope(scope)
        return jdbc.query(
            "select metadata from artifact_records where company_id=? and application_id=? order by id",
            { rs, _ -> mapper.readValue<ArtifactRecord>(rs.getString(1)) },
            scope.companyId,
            scope.applicationId,
        )
    }

    fun prepare(record: ArtifactRecord): ArtifactRecord {
        verifyScope(record.scope, bind = true)
        val input = mapper.readValue<ArtifactInput>(record.inputJson)
        ArtifactRules.validate(record, input, ArtifactRenderer.TEMPLATE_VERSION)
        jdbc.update(
            """insert into artifact_records(id,company_id,application_id,offer_id,kind,phase,template_version,input_hash,metadata)
            values(?,?,?,?,?,?,?,?,?::jsonb) on conflict(application_id,offer_id,kind,phase,template_version,input_hash) do nothing""",
            record.id, record.scope.companyId, record.scope.applicationId, record.offerId, record.kind, record.phase, record.templateVersion, record.inputHash,
            mapper.writeValueAsString(
                record,
            ),
        )
        return artifacts(record.scope).single {
            it.offerId == record.offerId &&
                it.kind == record.kind &&
                it.phase == record.phase &&
                it.templateVersion == record.templateVersion &&
                it.inputHash == record.inputHash
        }
    }

    fun retry(scope: DocumentScope, id: UUID): ArtifactRecord {
        val record = artifacts(scope).firstOrNull { it.id == id } ?: throw NoSuchElementException()
        if (record.status == "READY") return record
        if (record.templateVersion != ArtifactRenderer.TEMPLATE_VERSION) {
            require(record.templateVersion < ArtifactRenderer.TEMPLATE_VERSION)
            val replacement = UUID.randomUUID()
            return prepare(
                record.copy(
                    id = replacement,
                    templateVersion = ArtifactRenderer.TEMPLATE_VERSION,
                    storedName = "generated-$replacement.${ArtifactKind.valueOf(record.kind).extension}",
                    status = "PENDING",
                    sizeBytes = null,
                    checksum = null,
                    readyAt = null,
                ),
            )
        }
        check(
            jdbc.update(
                "update artifact_records set status='PENDING',attempts=0,next_attempt_at=now(),metadata=?::jsonb where id=? and (lease_until is null or lease_until < now())",

                mapper.writeValueAsString(record.copy(status = "PENDING")),
                id,
            ) ==
                1,
        )
        return record.copy(status = "PENDING")
    }

    fun artifactBytes(scope: DocumentScope, id: UUID): ByteArray {
        val record = artifacts(scope).firstOrNull { it.id == id } ?: throw NoSuchElementException()
        check(record.status == "READY")
        return bytes(record.storedName, record.backend, record.sizeBytes!!, record.checksum!!)
    }

    private fun bytes(name: String, backend: String, size: Long, checksum: String): ByteArray {
        val file = files.get(name, StorageBackend.valueOf(backend)) ?: error("Storage unavailable")
        val bytes = file.content.use { it.readNBytes(MAX_DOCUMENT_BYTES + 1) }
        check(bytes.size.toLong() == size && file.sizeBytes == size && sha256(bytes) == checksum)
        return bytes
    }

    @Scheduled(fixedDelayString = "\${cabinet.documents.poll-ms:1000}")
    fun generateOne() {
        val claim = tx.execute {
            val gate = jdbc.queryForMap("select owner,epoch from document_ownership where singleton for share")
            if (gate["owner"] != "DOCUMENTS") return@execute null
            val row =
                jdbc.queryForList(
                    "select id,metadata from artifact_records where template_version=${ArtifactRenderer.TEMPLATE_VERSION} and status<>'READY' and attempts<5 and next_attempt_at<=now() and (lease_until is null or lease_until<now()) order by next_attempt_at limit 1 for update skip locked",

                ).firstOrNull()
                    ?: return@execute null
            val token = UUID.randomUUID()
            jdbc.update("update artifact_records set lease_token=?,lease_until=now()+interval '60 seconds',attempts=attempts+1 where id=?", token, row["id"])
            Triple(mapper.readValue<ArtifactRecord>(row["metadata"].toString()), token, (gate["epoch"] as Number).toLong())
        } ?: return
        val (record, token, epoch) = claim
        try {
            val input = mapper.readValue<ArtifactInput>(record.inputJson)
            check(sha256(record.inputJson.toByteArray()) == record.inputHash)
            val rendered = renderer.render(ArtifactKind.valueOf(record.kind), input)
            require(rendered.size in 1..MAX_DOCUMENT_BYTES)
            owned(epoch) {
                // Token + lease lock protect the file itself, not just the metadata update.
                val active = jdbc.queryForList(
                    "select id from artifact_records where id=? and lease_token=? and lease_until>now() and status<>'READY' for update",
                    record.id,
                    token,
                ).isNotEmpty()
                if (!active) return@owned
                files.put(record.storedName, rendered.inputStream(), rendered.size.toLong(), StorageBackend.valueOf(record.backend))
                val ready = record.copy(status = "READY", sizeBytes = rendered.size.toLong(), checksum = sha256(rendered), readyAt = Instant.now())
                jdbc.update(
                    "update artifact_records set metadata=?::jsonb,status='READY',lease_token=null,lease_until=null where id=?",
                    mapper.writeValueAsString(ready),
                    record.id,
                )
                jdbc.update("insert into artifact_outbox(artifact_id) values(?) on conflict do nothing", record.id)
            }
        } catch (_: Exception) {
            // A failed stale worker is still a writer. Losing ownership or the claim must
            // never demote a record belonging to the next epoch or another live worker.
            runCatching {
                owned(epoch) {
                    jdbc.update(
                        "update artifact_records set status='ERROR',metadata=jsonb_set(metadata,'{status}','\"ERROR\"'),lease_token=null,lease_until=null,next_attempt_at=now()+interval '15 seconds' where id=? and lease_token=? and lease_until>now() and status<>'READY'",

                        record.id,
                        token,
                    )
                }
            }
        }
    }
}
