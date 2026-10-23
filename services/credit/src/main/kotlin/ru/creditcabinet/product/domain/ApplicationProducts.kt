package ru.creditcabinet.product.domain

import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.common.exception.NotFoundException

/** A submitted application never consults the mutable catalogue, even for a counter offer. */
fun ProductCatalog.forApplication(application: LoanApplication, code: String = application.terms.productCode): Product {
    val selected = application.productCatalogSnapshot[code]
        ?: application.productVersion.takeIf { code == application.terms.productCode }
        ?: throw NotFoundException("Продукта нет в условиях, зафиксированных при подаче заявки")
    return version(code, selected) ?: throw IllegalStateException("Зафиксированная версия продукта не найдена")
}

fun ProductCatalog.applicationCatalog(application: LoanApplication): List<Product> =
    application.productCatalogSnapshot.map { (code, version) ->
        version(code, version) ?: throw IllegalStateException("Зафиксированный каталог заявки неполон")
    }
