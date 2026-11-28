package ru.creditcabinet.application.infrastructure

import org.springframework.stereotype.Component
import ru.creditcabinet.application.application.CreditFailure.DependencyUnavailable
import ru.creditcabinet.application.application.DraftProductPreview
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductCatalog

@Component
class CatalogDraftProductPreview(private val products: ProductCatalog) : DraftProductPreview {
    override fun latest(productCode: String): Result<Product?> =
        try {
            Result.success(products.latest(productCode))
        } catch (failure: DependencyUnavailable) {
            Result.failure(failure)
        }
}
