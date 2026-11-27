package ru.creditcabinet.product.infrastructure

import ru.creditcabinet.application.infrastructure.creditPortCall
import ru.creditcabinet.catalogjdbc.JdbcProductCatalog
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductCatalog
import ru.creditcabinet.product.domain.ProductVersions
import ru.creditcabinet.product.domain.SubmissionProductCatalog
import java.time.Instant
import java.util.UUID

internal class LocalProductCatalog(private val catalog: JdbcProductCatalog) :
    ProductCatalog by catalog, ProductVersions by catalog, SubmissionProductCatalog by catalog {
    override fun create(
        product: Product,
        authorId: UUID,
        reason: String,
        now: Instant,
        authorName: String?,
        authorRole: String?,
    ) = creditPortCall { catalog.create(product, authorId, reason, now, authorName, authorRole) }

    override fun replace(
        product: Product,
        expectedVersion: Long,
        authorId: UUID,
        reason: String,
        now: Instant,
        authorName: String?,
        authorRole: String?,
    ) = creditPortCall { catalog.replace(product, expectedVersion, authorId, reason, now, authorName, authorRole) }
}
