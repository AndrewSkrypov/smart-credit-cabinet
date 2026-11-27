package ru.creditcabinet.catalog.domain

import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductRevision
import ru.creditcabinet.product.domain.SubmissionProductCatalog
import java.time.Instant
import java.util.UUID

fun interface CatalogAuthority {
    /** Fails while current catalog operations are fenced. */
    fun requireCurrent()
}

class CatalogFenced : RuntimeException("Catalog authority is fenced")

class CatalogUnavailable(cause: Throwable) : RuntimeException("Catalog data is unavailable", cause)

data class CatalogEditor(val id: UUID, val name: String?)

interface CatalogReader : SubmissionProductCatalog {
    fun current(code: String?, effectiveAt: Instant?): List<ProductRevision>
    fun version(code: String, version: Long): ProductRevision?
    fun history(code: String): List<ProductRevision>
}

/** Both writes enforce authority and commit the new revision and pointer atomically. */
interface CatalogWriter {
    fun create(product: Product, editor: CatalogEditor, reason: String, at: Instant): ProductRevision
    fun replace(product: Product, expectedVersion: Long, editor: CatalogEditor, reason: String, at: Instant): ProductRevision
}
