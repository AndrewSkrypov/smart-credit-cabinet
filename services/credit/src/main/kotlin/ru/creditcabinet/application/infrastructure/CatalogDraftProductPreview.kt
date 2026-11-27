package ru.creditcabinet.application.infrastructure

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import ru.creditcabinet.application.application.DraftProductPreview
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductCatalog

@Component
class CatalogDraftProductPreview(private val products: ProductCatalog) : DraftProductPreview {
    override fun latest(productCode: String): Result<Product?> =
        try {
            Result.success(products.latest(productCode))
        } catch (failure: ApiException) {
            if (failure.httpStatus != HttpStatus.SERVICE_UNAVAILABLE) throw failure
            Result.failure(failure)
        }
}
