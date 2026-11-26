package ru.creditcabinet.document.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.creditcabinet.document.application.ArtifactRegistration
import ru.creditcabinet.document.application.FrozenArtifactInput
import ru.creditcabinet.document.application.GeneratedArtifactStore
import ru.creditcabinet.document.application.QueuedArtifact
import ru.creditcabinet.document.domain.ArtifactGroup
import ru.creditcabinet.document.domain.ArtifactKind
import ru.creditcabinet.document.domain.ArtifactPhase
import ru.creditcabinet.document.domain.ArtifactRecord
import ru.creditcabinet.document.domain.ArtifactStatus
import ru.creditcabinet.document.domain.ArtifactStorage
import ru.creditcabinet.document.domain.StorageBackend
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Repository
class JdbcGeneratedArtifactStore(private val jdbc: JdbcTemplate) : GeneratedArtifactStore {
    override fun register(
        group: ArtifactGroup,
        phase: ArtifactPhase,
        inputJson: String,
        artifacts: List<ArtifactRegistration>,
        now: Instant,
    ): List<ArtifactRecord> {
        val timestamp = Timestamp.from(now)
        for (artifact in artifacts) {
            jdbc.update(
                """
                insert into generated_artifacts(id,application_id,offer_id,kind,phase,template_version,input_hash,input_json,title,file_name,content_type,stored_name,storage_backend,next_attempt_at,created_at,updated_at)
                values(?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?) on conflict(application_id,offer_id,kind,phase,template_version,input_hash) do nothing
                """.trimIndent(),
                artifact.id,
                group.applicationId,
                group.offerId,
                artifact.kind.name,
                phase.name,
                group.templateVersion,
                group.inputHash,
                inputJson,
                artifact.title,
                artifact.fileName,
                artifact.kind.contentType,
                artifact.storage.name,
                artifact.storage.backend.name,
                timestamp,
                timestamp,
                timestamp,
            )
        }
        return jdbc.query(
            "select * from generated_artifacts where application_id=? and offer_id=? and phase=? and input_hash=? and template_version=? order by kind",
            { rs, _ -> record(rs) },
            group.applicationId,
            group.offerId,
            phase.name,
            group.inputHash,
            group.templateVersion,
        )
    }

    override fun loansAwaitingFiles(templateVersion: Int): List<UUID> =
        jdbc.query(
            """
            select l.application_id from loans l where
            (select count(*) from generated_artifacts a where a.application_id=l.application_id and a.phase='FINAL' and a.template_version=?) < ?
            order by l.disbursed_at limit 5
            """.trimIndent(),
            { rs, _ -> rs.getObject(1, UUID::class.java) },
            templateVersion,
            ArtifactKind.entries.size,
        )

    override fun readyGroups(templateVersion: Int): List<ArtifactGroup> =
        jdbc.query(
            """
            select a.application_id,a.offer_id,a.template_version,a.input_hash
            from generated_artifacts a
            where a.phase='FINAL' and a.status='READY' and a.template_version=?
              and not exists(select 1 from generated_artifact_notices n where n.application_id=a.application_id
                and n.offer_id=a.offer_id and n.template_version=a.template_version and n.input_hash=a.input_hash)
            group by a.application_id,a.offer_id,a.template_version,a.input_hash having count(*)=?
            order by a.application_id limit 10
            """.trimIndent(),
            { rs, _ -> group(rs) },
            templateVersion,
            ArtifactKind.entries.size,
        )

    override fun claimReadyNotice(group: ArtifactGroup, now: Instant): Boolean {
        val statuses = jdbc.queryForList(
            "select status from generated_artifacts where application_id=? and offer_id=? and phase='FINAL' and template_version=? and input_hash=? order by kind for update",
            String::class.java,
            group.applicationId,
            group.offerId,
            group.templateVersion,
            group.inputHash,
        )
        if (statuses.size != ArtifactKind.entries.size || statuses.any { it != ArtifactStatus.READY.name }) return false
        return jdbc.update(
            "insert into generated_artifact_notices(application_id,offer_id,template_version,input_hash,queued_at) values(?,?,?,?,?) on conflict do nothing",
            group.applicationId,
            group.offerId,
            group.templateVersion,
            group.inputHash,
            Timestamp.from(now),
        ) > 0
    }

    override fun lockNextArtifact(templateVersion: Int, now: Instant): QueuedArtifact? =
        jdbc.query(
            """
            select id,kind,stored_name,storage_backend,input_json,input_hash from generated_artifacts
            where template_version=? and status <> 'READY' and attempts < 5 and next_attempt_at <= ?
            order by next_attempt_at limit 1 for update skip locked
            """.trimIndent(),
            { rs, _ ->
                QueuedArtifact(
                    rs.getObject("id", UUID::class.java),
                    ArtifactKind.valueOf(rs.getString("kind")),
                    storage(rs),
                    FrozenArtifactInput(rs.getString("input_json"), rs.getString("input_hash")),
                )
            },
            templateVersion,
            Timestamp.from(now),
        ).firstOrNull()

    override fun markReady(id: UUID, sizeBytes: Long, checksum: String, now: Instant) {
        jdbc.update(
            "update generated_artifacts set status='READY',size_bytes=?,checksum=?,attempts=attempts+1,error_code=null,updated_at=?,ready_at=? where id=?",
            sizeBytes,
            checksum,
            Timestamp.from(now),
            Timestamp.from(now),
            id,
        )
    }

    override fun markGenerationFailed(id: UUID, now: Instant) {
        jdbc.update(
            "update generated_artifacts set status='ERROR',attempts=attempts+1,error_code='GENERATION_FAILED',next_attempt_at=?,updated_at=? where id=?",
            Timestamp.from(now.plusSeconds(RETRY_DELAY_SECONDS)),
            Timestamp.from(now),
            id,
        )
    }

    override fun find(applicationId: UUID, artifactId: UUID, lock: Boolean): ArtifactRecord? {
        val lockClause = if (lock) " for update" else ""
        return jdbc.query(
            "select * from generated_artifacts where application_id=? and id=?$lockClause",
            { rs, _ -> record(rs) },
            applicationId,
            artifactId,
        ).firstOrNull()
    }

    override fun createReplacement(record: ArtifactRecord, replacementId: UUID, templateVersion: Int, now: Instant): UUID {
        val timestamp = Timestamp.from(now)
        jdbc.update(
            """
            insert into generated_artifacts(id,application_id,offer_id,kind,phase,template_version,input_hash,input_json,title,file_name,content_type,stored_name,storage_backend,next_attempt_at,created_at,updated_at)
            select ?,application_id,offer_id,kind,phase,?,input_hash,input_json,title,file_name,content_type,?,storage_backend,?,?,?
            from generated_artifacts where id=?
            on conflict(application_id,offer_id,kind,phase,template_version,input_hash) do nothing
            """.trimIndent(),
            replacementId,
            templateVersion,
            "generated-$replacementId.${record.kind.extension}",
            timestamp,
            timestamp,
            timestamp,
            record.id,
        )
        return checkNotNull(
            jdbc.queryForObject(
                """
                select current.id from generated_artifacts current join generated_artifacts old
                on current.application_id=old.application_id and current.offer_id=old.offer_id and current.kind=old.kind
                and current.phase=old.phase and current.input_hash=old.input_hash
                where old.id=? and current.template_version=?
                """.trimIndent(),
                UUID::class.java,
                record.id,
                templateVersion,
            ),
        )
    }

    override fun reset(id: UUID, now: Instant) {
        jdbc.update(
            "update generated_artifacts set status='PENDING',attempts=0,error_code=null,next_attempt_at=?,updated_at=? where id=?",
            Timestamp.from(now),
            Timestamp.from(now),
            id,
        )
    }

    override fun markStorageUnavailable(id: UUID, now: Instant) {
        jdbc.update(
            "update generated_artifacts set status='ERROR',error_code='STORAGE_UNAVAILABLE',attempts=0,next_attempt_at=?,updated_at=? where id=?",
            Timestamp.from(now.plusSeconds(RETRY_DELAY_SECONDS)),
            Timestamp.from(now),
            id,
        )
    }

    private fun group(rs: ResultSet): ArtifactGroup = ArtifactGroup(
        rs.getObject("application_id", UUID::class.java),
        rs.getObject("offer_id", UUID::class.java),
        rs.getInt("template_version"),
        rs.getString("input_hash"),
    )

    private fun storage(rs: ResultSet): ArtifactStorage =
        ArtifactStorage(rs.getString("stored_name"), StorageBackend.valueOf(rs.getString("storage_backend")))

    private fun record(rs: ResultSet): ArtifactRecord = ArtifactRecord(
        id = rs.getObject("id", UUID::class.java),
        group = group(rs),
        kind = ArtifactKind.valueOf(rs.getString("kind")),
        phase = ArtifactPhase.valueOf(rs.getString("phase")),
        title = rs.getString("title"),
        fileName = rs.getString("file_name"),
        contentType = rs.getString("content_type"),
        storage = storage(rs),
        status = ArtifactStatus.valueOf(rs.getString("status")),
        sizeBytes = rs.getObject("size_bytes", java.lang.Long::class.java)?.toLong(),
        checksum = rs.getString("checksum"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        readyAt = rs.getTimestamp("ready_at")?.toInstant(),
    )

    private companion object {
        const val RETRY_DELAY_SECONDS = 15L
    }
}
