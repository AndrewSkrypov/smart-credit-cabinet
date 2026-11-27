package ru.creditcabinet.documents.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.documents.ArtifactRecord
import ru.creditcabinet.documents.application.ArtifactClaim
import ru.creditcabinet.documents.application.ArtifactJobs
import ru.creditcabinet.documents.application.DocumentsOwnership
import ru.creditcabinet.documents.domain.ArtifactLifecycle
import ru.creditcabinet.documents.domain.ArtifactPublicationState
import java.util.UUID

class JdbcArtifactJobs(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val tx: TransactionTemplate,
    private val ownership: DocumentsOwnership,
) : ArtifactJobs {
    override fun claim(policy: ArtifactLifecycle): ArtifactClaim? = tx.execute {
        val gate = jdbc.queryForMap("select owner,epoch from document_ownership where singleton")
        if (gate["owner"] != "DOCUMENTS") return@execute null
        val epoch = (gate["epoch"] as Number).toLong()
        jdbc.queryForObject("select public.assert_documents_writer(?)", Boolean::class.java, epoch)
        // Atomic indexed selection is the SQL representation of the pure eligibility policy.
        val row = jdbc.queryForList(
            "select id,metadata,template_version,status,attempts from artifact_records where template_version=? and status<>'READY' and metadata->>'checksum' is null and metadata->>'readyAt' is null and attempts<? and next_attempt_at<=now() and (lease_until is null or lease_until<now()) order by next_attempt_at limit 1 for update skip locked",
            policy.templateVersion,
            policy.maxAttempts,
        ).firstOrNull() ?: return@execute null
        val record = mapper.readValue<ArtifactRecord>(row["metadata"].toString())
        check(
            policy.eligible(
                ArtifactPublicationState(
                    (row["template_version"] as Number).toInt(),
                    row["status"].toString(),
                    record.checksum != null,
                    record.readyAt != null,
                    (row["attempts"] as Number).toInt(),
                ),
            ),
        )
        val token = UUID.randomUUID()
        jdbc.update(
            "update artifact_records set lease_token=?,lease_until=now()+(? * interval '1 second'),attempts=attempts+1 where id=?",
            token,
            policy.leaseSeconds,
            row["id"],
        )
        ArtifactClaim(record, token, epoch)
    }

    override fun publish(claim: ArtifactClaim, produceReady: () -> ArtifactRecord) {
        ownership.owned(claim.epoch) {
            // Row lock first; expiry is evaluated with fresh DB time after any wait.
            jdbc.queryForList("select id from artifact_records where id=? for update", claim.record.id)
            val active = jdbc.queryForObject(
                "select exists(select 1 from artifact_records where id=? and lease_token=? and lease_until>clock_timestamp() and status<>'READY')",
                Boolean::class.java,
                claim.record.id,
                claim.token,
            )!!
            if (!active) return@owned
            val completed = jdbc.update(
                "update artifact_records set metadata=?::jsonb,status='READY',lease_token=null,lease_until=null where id=? and lease_token=? and lease_until>clock_timestamp() and status<>'READY'",
                mapper.writeValueAsString(produceReady()),
                claim.record.id,
                claim.token,
            )
            if (completed == 1) jdbc.update("insert into artifact_outbox(artifact_id) values(?) on conflict do nothing", claim.record.id)
        }
    }

    override fun fail(claim: ArtifactClaim, retrySeconds: Long) {
        ownership.owned(claim.epoch) {
            jdbc.queryForList("select id from artifact_records where id=? for update", claim.record.id)
            jdbc.update(
                "update artifact_records set status='ERROR',metadata=jsonb_set(metadata,'{status}','\"ERROR\"'),lease_token=null,lease_until=null,next_attempt_at=now()+(? * interval '1 second') where id=? and lease_token=? and lease_until>clock_timestamp() and status<>'READY'",
                retrySeconds,
                claim.record.id,
                claim.token,
            )
        }
    }
}
