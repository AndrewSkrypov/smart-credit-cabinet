package ru.creditcabinet.product.domain

import java.time.Instant
import java.util.UUID

data class ProductRevision(
    val product: Product,
    val authorId: UUID?,
    val reason: String,
    val createdAt: Instant,
    val authorName: String? = null,
    val authorRole: String? = null,
)

interface ProductVersions {
    fun list(): List<Product>
    fun history(code: String): List<ProductRevision>
    fun create(
        product: Product,
        authorId: UUID,
        reason: String,
        now: Instant,
        authorName: String? = null,
        authorRole: String? = null,
    ): ProductRevision
    fun replace(
        product: Product,
        expectedVersion: Long,
        authorId: UUID,
        reason: String,
        now: Instant,
        authorName: String? = null,
        authorRole: String? = null,
    ): ProductRevision
}
