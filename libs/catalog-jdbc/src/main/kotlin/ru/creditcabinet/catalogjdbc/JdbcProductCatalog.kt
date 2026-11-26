package ru.creditcabinet.catalogjdbc

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductCatalog
import ru.creditcabinet.product.domain.ProductLifecycleStatus
import ru.creditcabinet.product.domain.ProductRevision
import ru.creditcabinet.product.domain.ProductVersions
import ru.creditcabinet.product.domain.SubmissionProductCatalog
import ru.creditcabinet.product.domain.SubmissionProductSnapshot
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

class JdbcProductCatalog(private val jdbc: JdbcTemplate, private val clock: Clock) : ProductCatalog, ProductVersions, SubmissionProductCatalog {
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
            lifecycleStatus = ProductLifecycleStatus.valueOf(rs.getString("lifecycle_status")),
            effectiveFrom = rs.getTimestamp("effective_from")?.toInstant(),
            effectiveTo = rs.getTimestamp("effective_to")?.toInstant(),
            agentCommissionRateBps = rs.getInt("agent_commission_rate_bps"),
        )
    }

    private val revisionMapper = RowMapper { rs, row ->
        ProductRevision(
            mapper.mapRow(rs, row)!!,
            rs.getObject("author_id", UUID::class.java),
            rs.getString("reason"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("author_name"),
            rs.getString("author_role"),
        )
    }

    override fun all(): List<Product> = currentRevisions(effectiveAt = clock.instant()).map(ProductRevision::product)

    override fun find(code: String): Product? = latest(code)?.takeIf { it.active && it.isEffectiveAt(clock.instant()) }

    override fun latest(code: String): Product? = jdbc.query("$CURRENT where p.code = ?", mapper, code).firstOrNull()

    override fun version(code: String, version: Long): Product? =
        jdbc.query("select * from product_versions where code = ? and version = ?", mapper, code, version).firstOrNull()

    override fun list(): List<Product> = currentRevisions().map(ProductRevision::product)

    fun currentRevisions(code: String? = null, effectiveAt: Instant? = null): List<ProductRevision> {
        val sql = StringBuilder(CURRENT)
        val values = mutableListOf<Any>()
        sql.append(" where true")
        if (code != null) {
            sql.append(" and p.code = ?")
            values.add(code)
        }
        if (effectiveAt != null) {
            sql.append(" and v.lifecycle_status = 'ACTIVE'")
            sql.append(" and (v.effective_from is null or v.effective_from <= ?)")
            sql.append(" and (v.effective_to is null or v.effective_to >= ?)")
            values.add(Timestamp.from(effectiveAt))
            values.add(Timestamp.from(effectiveAt))
        }
        sql.append(" order by p.display_order, p.code")
        return jdbc.query(sql.toString(), revisionMapper, *values.toTypedArray())
    }

    override fun snapshotForSubmission(selectedProductCode: String): SubmissionProductSnapshot? {
        val at = clock.instant()
        val revisions = currentRevisions(effectiveAt = at)
        val selected = revisions.firstOrNull { it.product.code == selectedProductCode } ?: return null
        return SubmissionProductSnapshot(at, selected.product, revisions.map(ProductRevision::product), revisions)
    }

    fun revision(code: String, version: Long): ProductRevision? =
        jdbc.query("select * from product_versions where code = ? and version = ?", revisionMapper, code, version).firstOrNull()

    override fun history(code: String): List<ProductRevision> = jdbc.query(
        "select * from product_versions where code = ? order by version desc",
        revisionMapper,
        code,
    )

    override fun create(
        product: Product,
        authorId: UUID,
        reason: String,
        now: Instant,
        authorName: String?,
        authorRole: String?,
    ): ProductRevision {
        jdbc.execute("select pg_advisory_xact_lock(941528327)")
        val existing = jdbc.queryForList("select 1 from products where code = ? for update", product.code)
        if (existing.isNotEmpty()) {
            throw ConflictException("Продукт с кодом ${product.code} уже существует")
        }
        val saved = product.copy(version = 1)
        val purposesSql = saved.purposes.joinToString(",", "{", "}") { it.name }
        val displayOrder = jdbc.queryForObject("select coalesce(max(display_order), 0) + 10 from products", Int::class.java) ?: 10
        jdbc.update(
            """
            insert into products (code, name, description, purposes, min_amount, max_amount, min_term_months, max_term_months,
                base_rate, auto_approval_limit, revolving, display_order, current_version)
            values (?, ?, ?, ?::varchar[], ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            saved.code, saved.name, saved.description, purposesSql, saved.minAmount, saved.maxAmount,
            saved.minTermMonths, saved.maxTermMonths, saved.baseRate, saved.autoApprovalLimit,
            saved.revolving, displayOrder, saved.version,
        )
        insertVersion(saved, purposesSql, authorId, reason, now, authorName, authorRole)
        return checkNotNull(revision(saved.code, saved.version))
    }

    override fun replace(
        product: Product,
        expectedVersion: Long,
        authorId: UUID,
        reason: String,
        now: Instant,
        authorName: String?,
        authorRole: String?,
    ): ProductRevision {
        val current = jdbc.queryForList("select current_version from products where code = ? for update", product.code).firstOrNull()
            ?: throw NotFoundException("Продукт не найден")
        if ((current["current_version"] as Number).toLong() != expectedVersion) {
            throw ConflictException("Условия только что изменил другой администратор — обновите карточку и проверьте изменения")
        }
        val previous = version(product.code, expectedVersion)!!
        require(
            previous.copy(baseRate = previous.baseRate.stripTrailingZeros()) !=
                product.copy(version = expectedVersion, baseRate = product.baseRate.stripTrailingZeros()),
        ) {
            "Условия не изменились — новая версия не требуется"
        }
        val saved = product.copy(version = expectedVersion + 1)
        val purposesSql = saved.purposes.joinToString(",", "{", "}") { it.name }
        insertVersion(saved, purposesSql, authorId, reason, now, authorName, authorRole)
        jdbc.update("update products set current_version = ? where code = ?", saved.version, saved.code)
        return checkNotNull(revision(saved.code, saved.version))
    }

    private fun insertVersion(
        saved: Product,
        purposesSql: String,
        authorId: UUID,
        reason: String,
        now: Instant,
        authorName: String?,
        authorRole: String?,
    ) {
        jdbc.update(
            """
            insert into product_versions (code, version, name, description, purposes, min_amount, max_amount, min_term_months, max_term_months,
                base_rate, auto_approval_limit, revolving, active, lifecycle_status, effective_from, effective_to, agent_commission_rate_bps,
                author_id, reason, created_at, author_name, author_role)
            values (?, ?, ?, ?, ?::varchar[], ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            saved.code, saved.version, saved.name, saved.description, purposesSql,
            saved.minAmount, saved.maxAmount, saved.minTermMonths, saved.maxTermMonths, saved.baseRate, saved.autoApprovalLimit,
            saved.revolving, saved.active, saved.lifecycleStatus.name,
            saved.effectiveFrom?.let(Timestamp::from), saved.effectiveTo?.let(Timestamp::from), saved.agentCommissionRateBps,
            authorId, reason, Timestamp.from(now), authorName, authorRole,
        )
    }

    private companion object {
        const val CURRENT = "select v.* from products p join product_versions v on v.code = p.code and v.version = p.current_version"
    }
}
