package ru.creditcabinet.product.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Component
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductCatalog
import ru.creditcabinet.product.domain.ProductRevision
import ru.creditcabinet.product.domain.ProductVersions
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Component
class ProductJdbcCatalog(private val jdbc: JdbcTemplate) : ProductCatalog, ProductVersions {
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
            version = rs.getLong("version"),
            active = rs.getBoolean("active"),
        )
    }

    override fun all(): List<Product> = jdbc.query("$CURRENT where v.active order by p.display_order", mapper)

    override fun find(code: String): Product? = latest(code)?.takeIf { it.active }

    override fun latest(code: String): Product? = jdbc.query("$CURRENT where p.code = ?", mapper, code).firstOrNull()

    override fun version(code: String, version: Long): Product? =
        jdbc.query("select * from product_versions where code = ? and version = ?", mapper, code, version).firstOrNull()

    override fun list(): List<Product> = jdbc.query("$CURRENT order by p.display_order", mapper)

    override fun history(code: String): List<ProductRevision> = jdbc.query(
        "select * from product_versions where code = ? order by version desc",
        { rs, row ->
            ProductRevision(
                mapper.mapRow(rs, row)!!,
                rs.getObject("author_id", UUID::class.java),
                rs.getString("reason"),
                rs.getTimestamp("created_at").toInstant(),
            )
        },
        code,
    )

    override fun replace(product: Product, expectedVersion: Long, authorId: UUID, reason: String, now: Instant): ProductRevision {
        val current = jdbc.queryForList("select current_version from products where code = ? for update", product.code).firstOrNull()
            ?: throw NotFoundException("Продукт не найден")
        if ((current["current_version"] as Number).toLong() != expectedVersion) {
            throw ConflictException("Условия только что изменил другой администратор — обновите карточку и проверьте изменения")
        }
        val saved = product.copy(version = expectedVersion + 1)
        jdbc.update(
            """
            insert into product_versions (code, version, name, description, purposes, min_amount, max_amount, min_term_months, max_term_months,
                base_rate, auto_approval_limit, revolving, active, author_id, reason, created_at)
            values (?, ?, ?, ?, ?::varchar[], ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            saved.code, saved.version, saved.name, saved.description, saved.purposes.joinToString(",", "{", "}") { it.name },
            saved.minAmount, saved.maxAmount, saved.minTermMonths, saved.maxTermMonths, saved.baseRate, saved.autoApprovalLimit,
            saved.revolving, saved.active, authorId, reason, Timestamp.from(now),
        )
        jdbc.update("update products set current_version = ? where code = ?", saved.version, saved.code)
        return ProductRevision(saved, authorId, reason, now)
    }

    private companion object {
        const val CURRENT = "select v.* from products p join product_versions v on v.code = p.code and v.version = p.current_version"
    }
}
