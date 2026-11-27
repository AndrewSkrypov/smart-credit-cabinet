package ru.creditcabinet.product.domain

data class ProductImpactCounts(
    val draftApplicationsCount: Long,
    val inProgressApplicationsCount: Long,
    val activePreApprovedOffersCount: Long,
)

data class ProductImpact(
    val productCode: String,
    val currentVersion: Long,
    val lifecycleStatus: ProductLifecycleStatus,
    val draftApplicationsCount: Long,
    val inProgressApplicationsCount: Long,
    val activePreApprovedOffersCount: Long,
    val archivalRule: String,
)

interface ProductImpactQuery {
    fun counts(productCode: String): ProductImpactCounts
}
