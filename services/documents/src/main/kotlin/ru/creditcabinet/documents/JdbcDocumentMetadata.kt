package ru.creditcabinet.documents

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class JdbcDocumentMetadata(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) : DocumentMetadata {
    override fun find(id: UUID): DocumentRecord? = jdbc.query("select metadata from document_records where id=? for update", { rs, _ -> mapper.readValue<DocumentRecord>(rs.getString(1)) }, id).firstOrNull()
    override fun reserve(record: DocumentRecord) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))", { _, _ -> true }, record.storedName)
        require(jdbc.queryForObject("select not exists(select 1 from document_records where metadata->>'storedName'=?) and not exists(select 1 from artifact_records where metadata->>'storedName'=?)", Boolean::class.java, record.storedName, record.storedName)!!)
    }
    override fun insert(record: DocumentRecord) {
        jdbc.update("insert into document_records(id,company_id,application_id,context,metadata) values(?,?,?,?,?::jsonb)", record.id, record.scope.companyId, record.scope.applicationId, record.context, mapper.writeValueAsString(record))
    }
}
