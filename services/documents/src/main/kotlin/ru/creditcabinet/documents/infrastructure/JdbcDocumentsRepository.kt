package ru.creditcabinet.documents.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.documents.ArtifactRecord
import ru.creditcabinet.documents.DocumentRecord
import ru.creditcabinet.documents.DocumentScope
import ru.creditcabinet.documents.application.ArtifactRecords
import ru.creditcabinet.documents.application.DocumentRecords
import ru.creditcabinet.documents.application.DocumentsOwnership
import java.util.UUID

/** All methods join the inbound ownership transaction; retry never releases its row lock. */
class JdbcDocumentsRepository(
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val tx: TransactionTemplate,
) : DocumentRecords, ArtifactRecords, DocumentsOwnership {
    override fun <T> owned(epoch: Long, action: () -> T): T = tx.execute {
        val gate = jdbc.queryForMap("select owner,epoch from document_ownership where singleton")
        check(gate["owner"] == "DOCUMENTS" && (gate["epoch"] as Number).toLong() == epoch) { "Ownership fenced" }
        jdbc.queryForObject("select public.assert_documents_writer(?)", Boolean::class.java, epoch)
        action()
    }!!

    override fun verifyScope(scope: DocumentScope, bind: Boolean) {
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

    override fun documents(scope: DocumentScope): List<DocumentRecord> = jdbc.query(
        "select metadata from document_records where company_id=? and application_id=? order by created_at,id",
        { rs, _ -> mapper.readValue<DocumentRecord>(rs.getString(1)) },
        scope.companyId,
        scope.applicationId,
    )

    override fun saveDeleted(record: DocumentRecord) {
        jdbc.update("update document_records set metadata=?::jsonb where id=?", mapper.writeValueAsString(record), record.id)
    }

    override fun artifacts(scope: DocumentScope): List<ArtifactRecord> = jdbc.query(
        "select metadata from artifact_records where company_id=? and application_id=? order by id",
        { rs, _ -> mapper.readValue<ArtifactRecord>(rs.getString(1)) },
        scope.companyId,
        scope.applicationId,
    )

    override fun insertArtifact(record: ArtifactRecord) {
        jdbc.update(
            """insert into artifact_records(id,company_id,application_id,offer_id,kind,phase,template_version,input_hash,metadata)
            values(?,?,?,?,?,?,?,?,?::jsonb) on conflict(application_id,offer_id,kind,phase,template_version,input_hash) do nothing""",
            record.id, record.scope.companyId, record.scope.applicationId, record.offerId, record.kind, record.phase, record.templateVersion, record.inputHash,
            mapper.writeValueAsString(record),
        )
    }

    override fun lockArtifact(scope: DocumentScope, id: UUID): ArtifactRecord? = jdbc.query(
        "select metadata from artifact_records where id=? and company_id=? and application_id=? for update",
        { rs, _ -> mapper.readValue<ArtifactRecord>(rs.getString(1)) },
        id,
        scope.companyId,
        scope.applicationId,
    ).firstOrNull()

    override fun retireExpiredLease(id: UUID): Boolean = jdbc.update(
        "update artifact_records set lease_token=null,lease_until=null where id=? and status<>'READY' and (lease_until is null or lease_until<clock_timestamp())",
        id,
    ) == 1

    override fun resetPending(record: ArtifactRecord): Boolean = jdbc.update(
        "update artifact_records set status='PENDING',attempts=0,next_attempt_at=now(),metadata=?::jsonb where id=? and status<>'READY' and (lease_until is null or lease_until < clock_timestamp())",
        mapper.writeValueAsString(record),
        record.id,
    ) == 1
}
