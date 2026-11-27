package ru.creditcabinet.product.domain

import java.time.Instant

/** Current product terms evaluated together for a submission. */
data class SubmissionProductSnapshot(
    val evaluatedAt: Instant,
    val selected: Product,
    val products: List<Product>,
    val revisions: List<ProductRevision> = emptyList(),
) {
    val versions: Map<String, Long>
        get() = products.associate { it.code to it.version }
}

fun interface SubmissionProductCatalog {
    /** Returns null when the selected product is absent from the effective catalogue. */
    fun snapshotForSubmission(selectedProductCode: String): SubmissionProductSnapshot?
}
