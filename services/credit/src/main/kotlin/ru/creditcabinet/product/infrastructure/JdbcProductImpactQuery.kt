package ru.creditcabinet.product.infrastructure

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import ru.creditcabinet.product.domain.ProductImpactCounts
import ru.creditcabinet.product.domain.ProductImpactQuery

@Component
class JdbcProductImpactQuery(
    private val jdbc: NamedParameterJdbcTemplate,
) : ProductImpactQuery {
    override fun counts(productCode: String): ProductImpactCounts {
        val drafts = jdbc.queryForObject(
            """
            select count(*) from applications
            where product_code = :code and status in ('DRAFT', 'PENDING_CLIENT_CONFIRMATION')
            """.trimIndent(),
            mapOf("code" to productCode),
            Long::class.java,
        ) ?: 0L
        val inProgress = jdbc.queryForObject(
            """
            select count(*) from applications
            where product_code = :code
              and status in ('SUBMITTED', 'INFO_REQUESTED', 'ENRICHMENT', 'SCORING', 'MANUAL_REVIEW', 'OFFER_ISSUED', 'SIGNING', 'DISBURSING')
            """.trimIndent(),
            mapOf("code" to productCode),
            Long::class.java,
        ) ?: 0L
        val offers = jdbc.queryForObject(
            """
            select count(*) from pre_approved_offers
            where product_code = :code and status = 'ACTIVE' and valid_until > now()
            """.trimIndent(),
            mapOf("code" to productCode),
            Long::class.java,
        ) ?: 0L
        return ProductImpactCounts(
            draftApplicationsCount = drafts,
            inProgressApplicationsCount = inProgress,
            activePreApprovedOffersCount = offers,
        )
    }
}
