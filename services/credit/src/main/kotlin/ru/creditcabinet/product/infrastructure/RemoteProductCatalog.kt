package ru.creditcabinet.product.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import ru.creditcabinet.cataloggrpc.CatalogGrpcClient
import ru.creditcabinet.catalogjdbc.JdbcProductCatalog
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.exception.UnauthorizedException
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductCatalog
import ru.creditcabinet.product.domain.ProductRevision
import ru.creditcabinet.product.domain.ProductVersions
import ru.creditcabinet.product.domain.SubmissionProductCatalog
import ru.creditcabinet.product.domain.SubmissionProductSnapshot
import java.time.Instant
import java.util.UUID

internal class RemoteProductCatalog(
    private val client: CatalogGrpcClient,
    private val local: JdbcProductCatalog,
    private val cache: CatalogRevisionCache,
) : ProductCatalog, ProductVersions, SubmissionProductCatalog {
    override fun all(): List<Product> = client.current(effectiveOnly = true).map(ProductRevision::product)
    override fun find(code: String): Product? = client.current(code, effectiveOnly = true).singleOrNull()?.product
    override fun latest(code: String): Product? = client.current(code).singleOrNull()?.product
    override fun version(code: String, version: Long): Product? = local.version(code, version)
    override fun list(): List<Product> = client.current().map(ProductRevision::product)
    override fun history(code: String): List<ProductRevision> = client.history(code)

    override fun forDraft(code: String): Product? {
        val revision = client.current(code, effectiveOnly = true).singleOrNull() ?: return null
        cache.store(listOf(revision))
        return revision.product
    }

    override fun snapshotForSubmission(selectedProductCode: String): SubmissionProductSnapshot? {
        val snapshot = client.snapshotForSubmission(selectedProductCode) ?: return null
        if (snapshot.revisions.isEmpty()) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Каталог не передал аудит редакций")
        }
        cache.store(snapshot.revisions)
        return snapshot
    }

    override fun create(
        product: Product,
        authorId: UUID,
        reason: String,
        now: Instant,
        authorName: String?,
        authorRole: String?,
    ): ProductRevision = client.create(product, reason, authorization())

    override fun replace(
        product: Product,
        expectedVersion: Long,
        authorId: UUID,
        reason: String,
        now: Instant,
        authorName: String?,
        authorRole: String?,
    ): ProductRevision = client.replace(product, expectedVersion, reason, authorization())

    private fun authorization(): String = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)
        ?.request?.getHeader(HttpHeaders.AUTHORIZATION)?.takeIf { it.startsWith("Bearer ") }
        ?: throw UnauthorizedException()
}

internal class CatalogRevisionCache(private val jdbc: JdbcTemplate, private val mapper: ObjectMapper) {
    fun store(revisions: List<ProductRevision>) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) { "Catalog revisions must be pinned in the Credit transaction" }
        val payload = mapper.writeValueAsString(revisions.map(::row))
        jdbc.execute { connection: java.sql.Connection ->
            connection.prepareStatement("select catalog_cutover.store_revisions(cast(? as jsonb))").use { statement ->
                statement.setString(1, payload)
                statement.execute()
            }
        }
    }

    private fun row(revision: ProductRevision): Map<String, Any?> = with(revision.product) {
        mapOf(
            "code" to code,
            "version" to version,
            "name" to name,
            "description" to description,
            "purposes" to purposes.map { it.name },
            "min_amount" to minAmount,
            "max_amount" to maxAmount,
            "min_term_months" to minTermMonths,
            "max_term_months" to maxTermMonths,
            "base_rate" to baseRate,
            "auto_approval_limit" to autoApprovalLimit,
            "revolving" to revolving,
            "active" to active,
            "lifecycle_status" to lifecycleStatus.name,
            "effective_from" to effectiveFrom?.toString(),
            "effective_to" to effectiveTo?.toString(),
            "agent_commission_rate_bps" to agentCommissionRateBps,
            "author_id" to revision.authorId,
            "reason" to revision.reason,
            "created_at" to revision.createdAt.toString(),
            "author_name" to revision.authorName,
            "author_role" to revision.authorRole,
        )
    }
}
