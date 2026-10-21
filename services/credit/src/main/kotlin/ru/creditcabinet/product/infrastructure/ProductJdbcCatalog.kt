package ru.creditcabinet.product.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductCatalog

@Component
class ProductJdbcCatalog(private val jdbc: JdbcTemplate) : ProductCatalog {
    private val mapper = RowMapper { rs, _ ->
        @Suppress("UNCHECKED_CAST")
        val purposes = (rs.getArray("purposes").array as Array<String>).map(FinancingPurpose::valueOf).toSet()
        Product(
            code = rs.getString("code"),
            name = rs.getString("name"),
            description = rs.getString("description"),
            purposes = purposes,
            minAmount = rs.getLong("min_amount"),
            maxAmount = rs.getLong("max_amount"),
            minTermMonths = rs.getInt("min_term_months"),
            maxTermMonths = rs.getInt("max_term_months"),
            baseRate = rs.getBigDecimal("base_rate"),
            autoApprovalLimit = rs.getLong("auto_approval_limit"),
            revolving = rs.getBoolean("revolving"),
        )
    }

    override fun all(): List<Product> = jdbc.query("select * from products order by display_order", mapper)

    override fun find(code: String): Product? = jdbc.query("select * from products where code = ?", mapper, code).firstOrNull()
}
