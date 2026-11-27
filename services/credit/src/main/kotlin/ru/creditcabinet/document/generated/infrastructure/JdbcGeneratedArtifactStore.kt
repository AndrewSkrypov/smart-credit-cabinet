package ru.creditcabinet.document.generated.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.stereotype.Repository
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.generated.ArtifactInput
import ru.creditcabinet.document.generated.ArtifactKind
import ru.creditcabinet.document.generated.ArtifactPhase
import ru.creditcabinet.document.generated.application.ArtifactFile
import ru.creditcabinet.document.generated.application.ArtifactInputCodec
import ru.creditcabinet.document.generated.application.ArtifactInputKey
import ru.creditcabinet.document.generated.application.ArtifactStatus
import ru.creditcabinet.document.generated.application.ArtifactView
import ru.creditcabinet.document.generated.application.ArtifactWork
import ru.creditcabinet.document.generated.application.GeneratedArtifactStore
import ru.creditcabinet.document.generated.application.PendingArtifactInput
import ru.creditcabinet.documents.ArtifactRecord
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Component
class JacksonArtifactInputCodec(private val mapper: ObjectMapper) : ArtifactInputCodec {
    override fun encode(input: ArtifactInput): String = mapper.writeValueAsString(input)

    override fun encodeBytes(input: ArtifactInput): ByteArray = mapper.writeValueAsBytes(input)

    override fun decode(encoded: String): ArtifactInput = mapper.readValue(encoded)
}

@Repository
class JdbcGeneratedArtifactStore(private val jdbc: JdbcTemplate, private val codec: ArtifactInputCodec) : GeneratedArtifactStore {
    override fun frozenFinal(applicationId: UUID): ArtifactInput? =
        jdbc.query("select input_json from document_artifact_inputs where application_id=? and phase='FINAL' order by created_at limit 1", { row, _ ->
            codec.decode(row.getString(1))
        }, applicationId).firstOrNull()

    override fun freeze(key: ArtifactInputKey, canonical: String, backend: StorageBackend): String {
        jdbc.update(
            "insert into document_artifact_inputs(application_id,offer_id,phase,input_hash,input_json,requested_backend) values(?,?,?,?,?::jsonb,?) on conflict do nothing",
            key.applicationId,
            key.offerId,
            key.phase.name,
            key.hash,
            canonical,
            backend.name,
        )
        return if (key.phase == ArtifactPhase.FINAL) {
            jdbc.queryForObject(
                "select input_json from document_artifact_inputs where application_id=? and offer_id=? and phase='FINAL'",
                String::class.java,
                key.applicationId,
                key.offerId,
            )!!
        } else {
            jdbc.queryForObject(
                "select input_json from document_artifact_inputs where application_id=? and offer_id=? and phase=? and input_hash=?",
                String::class.java,
                key.applicationId,
                key.offerId,
                key.phase.name,
                key.hash,
            )!!
        }
    }

    override fun requestedBackend(key: ArtifactInputKey): StorageBackend? =
        jdbc.query(
            "select requested_backend from document_artifact_inputs where application_id=? and offer_id=? and phase=? and input_hash=?",
            { row, _ -> StorageBackend.valueOf(row.getString(1)) },
            key.applicationId,
            key.offerId,
            key.phase.name,
            key.hash,
        ).firstOrNull()

    override fun pendingInputs(): List<PendingArtifactInput> =
        jdbc.query(
            "select application_id,input_json,requested_backend from document_artifact_inputs where delivered_at is null order by created_at limit 5",
            { row, _ ->
                PendingArtifactInput(row.getObject(1, UUID::class.java), codec.decode(row.getString(2)), StorageBackend.valueOf(row.getString(3)))
            },
        )

    override fun markDelivered(key: ArtifactInputKey) {
        jdbc.update(
            "update document_artifact_inputs set delivered_at=now() where application_id=? and offer_id=? and phase=? and input_hash=? and delivered_at is null",
            key.applicationId,
            key.offerId,
            key.phase.name,
            key.hash,
        )
    }

    override fun isDelivered(key: ArtifactInputKey): Boolean =
        jdbc.queryForObject(
            "select exists(select 1 from document_artifact_inputs where application_id=? and offer_id=? and phase='FINAL' and input_hash=? and delivered_at is not null)",
            Boolean::class.java,
            key.applicationId,
            key.offerId,
            key.hash,
        )!!

    override fun isReferenced(key: ArtifactInputKey): Boolean =
        jdbc.queryForObject(
            "select exists(select 1 from document_artifact_references where application_id=? and offer_id=? and phase=? and input_hash=?)",
            Boolean::class.java,
            key.applicationId,
            key.offerId,
            key.phase.name,
            key.hash,
        )!!

    override fun loansWithoutRemoteArtifacts(): List<UUID> =
        jdbc.query(
            """
            select l.application_id from loans l where not exists(
                select 1 from document_artifact_inputs i where i.application_id=l.application_id and i.phase='FINAL' and i.delivered_at is not null
            ) order by l.disbursed_at limit 5
            """.trimIndent(),
            { row, _ -> row.getObject(1, UUID::class.java) },
        )

    override fun loansWithoutLocalArtifacts(templateVersion: Int): List<UUID> =
        jdbc.query(
            """
            select l.application_id from loans l where
            (select count(*) from generated_artifacts a where a.application_id=l.application_id and a.phase='FINAL' and a.template_version=?) < 3
            order by l.disbursed_at limit 5
            """.trimIndent(),
            { row, _ -> row.getObject(1, UUID::class.java) },
            templateVersion,
        )

    override fun putLocal(record: ArtifactRecord, at: Instant) {
        val now = Timestamp.from(at)
        jdbc.update(
            """
                insert into generated_artifacts(id,
                    application_id,
                    offer_id,
                    kind,
                    phase,
                    template_version,
                    input_hash,
                    input_json,
                    title,
                    file_name,
                    content_type,
                    stored_name,
                    storage_backend,
                    next_attempt_at,
                    created_at,
                    updated_at)
                values(?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?) on conflict(application_id,offer_id,kind,phase,template_version,input_hash) do nothing
            """.trimIndent(),
            record.id, record.scope.applicationId, record.offerId, record.kind, record.phase, record.templateVersion,
            record.inputHash, record.inputJson, record.title, record.fileName, record.contentType, record.storedName,
            record.backend, now, now, now,
        )
    }

    override fun listLocal(key: ArtifactInputKey, templateVersion: Int): List<ArtifactView> =
        jdbc.query(
            "select * from generated_artifacts where application_id=? and offer_id=? and phase=? and input_hash=? and template_version=? order by kind",
            { row, _ -> view(row) },
            key.applicationId,
            key.offerId,
            key.phase.name,
            key.hash,
            templateVersion,
        )

    override fun readyLocalGroups(templateVersion: Int): List<ArtifactInputKey> =
        jdbc.query(
            """
            select a.application_id,a.offer_id,a.template_version,a.input_hash
            from generated_artifacts a
            where a.phase='FINAL' and a.status='READY' and a.template_version=?
              and not exists(select 1 from generated_artifact_notices n where n.application_id=a.application_id
                and n.offer_id=a.offer_id and n.template_version=a.template_version and n.input_hash=a.input_hash)
            group by a.application_id,a.offer_id,a.template_version,a.input_hash having count(*)=3
            order by a.application_id limit 10
            """.trimIndent(),
            { row, _ ->
                ArtifactInputKey(
                    row.getObject("application_id", UUID::class.java),
                    row.getObject("offer_id", UUID::class.java),
                    ArtifactPhase.FINAL,
                    row.getString("input_hash"),
                )
            },
            templateVersion,
        )

    override fun lockGroupStatuses(key: ArtifactInputKey, templateVersion: Int): List<ArtifactStatus> =
        jdbc.queryForList(
            "select status from generated_artifacts where application_id=? and offer_id=? and phase='FINAL' and template_version=? and input_hash=? order by kind for update",
            String::class.java,
            key.applicationId,
            key.offerId,
            templateVersion,
            key.hash,
        ).map(ArtifactStatus::valueOf)

    override fun readyRemoteApplications(templateVersion: Int): List<UUID> =
        jdbc.query(
            """
            select distinct i.application_id from document_artifact_inputs i where i.phase='FINAL' and i.delivered_at is not null and not exists(
                select 1 from generated_artifact_notices n where n.application_id=i.application_id and n.offer_id=i.offer_id
                    and n.input_hash=i.input_hash and n.template_version=?
            ) limit 10
            """.trimIndent(),
            { row, _ -> row.getObject(1, UUID::class.java) },
            templateVersion,
        )

    override fun claimNotice(key: ArtifactInputKey, templateVersion: Int, at: Instant): Boolean =
        jdbc.update(
            "insert into generated_artifact_notices(application_id,offer_id,template_version,input_hash,queued_at) values(?,?,?,?,?) on conflict do nothing",
            key.applicationId,
            key.offerId,
            templateVersion,
            key.hash,
            Timestamp.from(at),
        ) !=
            0

    override fun lockNext(templateVersion: Int, at: Instant): ArtifactWork? =
        jdbc.query(
            "select * from generated_artifacts where template_version=? and status <> 'READY' and checksum is null and ready_at is null and attempts < 5 and next_attempt_at <= ? order by next_attempt_at limit 1 for update skip locked",
            { row, _ ->
                ArtifactWork(
                    row.getObject("id", UUID::class.java),
                    ArtifactKind.valueOf(row.getString("kind")),
                    row.getString("input_json"),
                    row.getString("input_hash"),
                    ArtifactFile(row.getString("stored_name"), StorageBackend.valueOf(row.getString("storage_backend"))),
                )
            },
            templateVersion,
            Timestamp.from(at),
        ).firstOrNull()

    override fun markReady(id: UUID, size: Long, checksum: String, at: Instant) {
        jdbc.update(
            "update generated_artifacts set status='READY',size_bytes=?,checksum=?,attempts=attempts+1,error_code=null,updated_at=?,ready_at=? where id=?",
            size,
            checksum,
            Timestamp.from(at),
            Timestamp.from(at),
            id,
        )
    }

    override fun markFailed(id: UUID, retryAt: Instant, at: Instant) {
        jdbc.update(
            "update generated_artifacts set status='ERROR',attempts=attempts+1,error_code='GENERATION_FAILED',next_attempt_at=?,updated_at=? where id=?",
            Timestamp.from(retryAt),
            Timestamp.from(at),
            id,
        )
    }

    override fun findLocal(applicationId: UUID, id: UUID, lock: Boolean): ArtifactView? {
        val sql = if (lock) {
            "select * from generated_artifacts where application_id=? and id=? for update"
        } else {
            "select * from generated_artifacts where application_id=? and id=?"
        }
        return jdbc.query(sql, { row, _ -> view(row) }, applicationId, id).firstOrNull()
    }

    override fun templateVersion(id: UUID): Int? =
        jdbc.queryForObject("select template_version from generated_artifacts where id=?", Int::class.java, id)

    override fun replaceLegacy(id: UUID, replacement: UUID, fileName: String, templateVersion: Int, at: Instant): UUID {
        val now = Timestamp.from(at)
        jdbc.update(
            """
                insert into generated_artifacts(id,
                    application_id,
                    offer_id,
                    kind,
                    phase,
                    template_version,
                    input_hash,
                    input_json,
                    title,
                    file_name,
                    content_type,
                    stored_name,
                    storage_backend,
                    next_attempt_at,
                    created_at,
                    updated_at)
                select ?,application_id,offer_id,kind,phase,?,input_hash,input_json,title,file_name,content_type,?,storage_backend,?,?,?
                from generated_artifacts where id=?
                on conflict(application_id,offer_id,kind,phase,template_version,input_hash) do nothing
            """.trimIndent(),
            replacement,
            templateVersion,
            fileName,
            now,
            now,
            now,
            id,
        )
        return jdbc.queryForObject(
            """
                select current.id from generated_artifacts current join generated_artifacts old
                on current.application_id=old.application_id and current.offer_id=old.offer_id and current.kind=old.kind
                and current.phase=old.phase and current.input_hash=old.input_hash
                where old.id=? and current.template_version=?
            """.trimIndent(),
            UUID::class.java,
            id,
            templateVersion,
        )!!
    }

    override fun reset(id: UUID, nextAttemptAt: Instant, updatedAt: Instant) {
        jdbc.update(
            "update generated_artifacts set status='PENDING',attempts=0,error_code=null,next_attempt_at=?,updated_at=? where id=?",
            Timestamp.from(nextAttemptAt),
            Timestamp.from(updatedAt),
            id,
        )
    }

    override fun file(id: UUID): ArtifactFile =
        jdbc.queryForObject("select stored_name,storage_backend from generated_artifacts where id=?", { row, _ ->
            ArtifactFile(row.getString("stored_name"), StorageBackend.valueOf(row.getString("storage_backend")))
        }, id)!!

    private fun view(rs: ResultSet): ArtifactView {
        val status = ArtifactStatus.valueOf(rs.getString("status"))
        val id = rs.getObject("id", UUID::class.java)
        val application = rs.getObject("application_id", UUID::class.java)
        return ArtifactView(
            id,
            ArtifactKind.valueOf(
                rs.getString("kind"),
            ),
            ArtifactPhase.valueOf(rs.getString("phase")), rs.getString("title"), rs.getString("file_name"), rs.getString("content_type"),
            status,
            rs.getObject(
                "size_bytes",
                java.lang.Long::class.java,
            )?.toLong(),
            rs.getString("checksum"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("ready_at")?.toInstant(),
            if (status == ArtifactStatus.ERROR) "Не удалось подготовить файл. Можно повторить создание." else null,
            if (status == ArtifactStatus.READY) "/api/v1/applications/$application/artifacts/$id/download" else null,
        )
    }
}
