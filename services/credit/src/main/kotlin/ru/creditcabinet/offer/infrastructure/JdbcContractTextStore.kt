package ru.creditcabinet.offer.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import ru.creditcabinet.offer.ContractTextSnapshot
import ru.creditcabinet.offer.application.ContractTextStore
import java.sql.Timestamp
import java.util.UUID

@Repository
class JdbcContractTextStore(private val jdbc: JdbcTemplate) : ContractTextStore {
    override fun find(offerId: UUID): ContractTextSnapshot? = jdbc.query(
        "select * from contract_text_snapshots where offer_id=?",
        { rs, _ ->
            ContractTextSnapshot(
                rs.getObject("id", UUID::class.java),
                rs.getObject("application_id", UUID::class.java),
                rs.getObject("offer_id", UUID::class.java),
                rs.getInt("template_version"),
                rs.getString("contract_text"),
                rs.getString("sha256"),
                rs.getTimestamp("created_at").toInstant(),
            )
        },
        offerId,
    ).firstOrNull()

    override fun lockOffer(applicationId: UUID, offerId: UUID) {
        jdbc.queryForObject("select id from offers where id=? and application_id=? for update", UUID::class.java, offerId, applicationId)
    }

    override fun insert(snapshot: ContractTextSnapshot) {
        jdbc.update(
            "insert into contract_text_snapshots(id,application_id,offer_id,template_version,contract_text,sha256,created_at) values(?,?,?,?,?,?,?)",
            snapshot.id,
            snapshot.applicationId,
            snapshot.offerId,
            snapshot.templateVersion,
            snapshot.text,
            snapshot.sha256,
            Timestamp.from(snapshot.createdAt),
        )
    }
}
