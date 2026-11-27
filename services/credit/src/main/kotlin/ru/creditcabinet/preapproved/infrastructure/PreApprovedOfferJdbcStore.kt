package ru.creditcabinet.preapproved.infrastructure

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import ru.creditcabinet.preapproved.domain.PreApprovedOffer
import ru.creditcabinet.preapproved.domain.PreApprovedOfferStatus
import ru.creditcabinet.preapproved.domain.PreApprovedOfferStore
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Component
class PreApprovedOfferJdbcStore(
    private val jdbc: NamedParameterJdbcTemplate,
) : PreApprovedOfferStore {
    override fun find(id: UUID): PreApprovedOffer? =
        jdbc.query(
            "select * from pre_approved_offers where id = :id",
            mapOf("id" to id),
        ) { rs, _ -> mapRow(rs) }.firstOrNull()

    override fun create(offer: PreApprovedOffer) {
        jdbc.update(
            """
            insert into pre_approved_offers
                (id, company_id, product_code, max_amount, min_term_months, max_term_months,
                 rate, valid_until, status, application_id, created_at, updated_at)
            values
                (:id, :companyId, :productCode, :maxAmount, :minTermMonths, :maxTermMonths,
                 :rate, :validUntil, :status, :applicationId, :createdAt, :updatedAt)
            """.trimIndent(),
            mapOf(
                "id" to offer.id,
                "companyId" to offer.companyId,
                "productCode" to offer.productCode,
                "maxAmount" to offer.maxAmount,
                "minTermMonths" to offer.minTermMonths,
                "maxTermMonths" to offer.maxTermMonths,
                "rate" to offer.rate,
                "validUntil" to Timestamp.from(offer.validUntil),
                "status" to offer.status.name,
                "applicationId" to offer.applicationId,
                "createdAt" to Timestamp.from(offer.createdAt),
                "updatedAt" to Timestamp.from(offer.updatedAt),
            ),
        )
    }

    override fun listActiveByCompany(companyId: UUID, now: Instant): List<PreApprovedOffer> =
        jdbc.query(
            """
            select * from pre_approved_offers
            where company_id = :companyId and status = 'ACTIVE' and valid_until > :now
            order by created_at desc
            """.trimIndent(),
            mapOf("companyId" to companyId, "now" to Timestamp.from(now)),
        ) { rs, _ -> mapRow(rs) }

    override fun listActiveByCompanies(companyIds: Collection<UUID>, now: Instant): List<PreApprovedOffer> {
        if (companyIds.isEmpty()) return emptyList()
        return jdbc.query(
            """
            select * from pre_approved_offers
            where company_id in (:companyIds) and status = 'ACTIVE' and valid_until > :now
            order by created_at desc
            """.trimIndent(),
            mapOf("companyIds" to companyIds, "now" to Timestamp.from(now)),
        ) { rs, _ -> mapRow(rs) }
    }

    override fun markUsed(id: UUID, applicationId: UUID, now: Instant): Boolean {
        val updated = jdbc.update(
            """
            update pre_approved_offers
            set status = 'USED', application_id = :applicationId, updated_at = :now
            where id = :id and status = 'ACTIVE' and valid_until > :now
            """.trimIndent(),
            mapOf("id" to id, "applicationId" to applicationId, "now" to Timestamp.from(now)),
        )
        return updated == 1
    }

    override fun release(id: UUID, applicationId: UUID, now: Instant): Boolean {
        val updated = jdbc.update(
            """
            update pre_approved_offers
            set status = case when valid_until > :now then 'ACTIVE' else 'EXPIRED' end,
                application_id = null,
                updated_at = :now
            where id = :id and status = 'USED' and application_id = :applicationId
            """.trimIndent(),
            mapOf("id" to id, "applicationId" to applicationId, "now" to Timestamp.from(now)),
        )
        return updated == 1
    }

    private fun mapRow(rs: ResultSet): PreApprovedOffer =
        PreApprovedOffer(
            id = rs.getObject("id", UUID::class.java),
            companyId = rs.getObject("company_id", UUID::class.java),
            productCode = rs.getString("product_code"),
            maxAmount = rs.getLong("max_amount"),
            minTermMonths = rs.getInt("min_term_months"),
            maxTermMonths = rs.getInt("max_term_months"),
            rate = rs.getBigDecimal("rate"),
            validUntil = rs.getTimestamp("valid_until").toInstant(),
            status = PreApprovedOfferStatus.valueOf(rs.getString("status")),
            applicationId = rs.getObject("application_id", UUID::class.java),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
}
