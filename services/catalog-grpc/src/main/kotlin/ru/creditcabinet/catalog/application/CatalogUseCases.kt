package ru.creditcabinet.catalog.application

import ru.creditcabinet.catalog.domain.CatalogAuthority
import ru.creditcabinet.catalog.domain.CatalogEditor
import ru.creditcabinet.catalog.domain.CatalogReader
import ru.creditcabinet.catalog.domain.CatalogWriter
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductLifecycleStatus
import ru.creditcabinet.product.domain.ProductRevision
import ru.creditcabinet.product.domain.SubmissionProductCatalog
import ru.creditcabinet.product.domain.SubmissionProductSnapshot
import ru.creditcabinet.product.domain.problems
import ru.creditcabinet.product.domain.validProductCode
import java.time.Clock

interface Catalog : SubmissionProductCatalog {
    fun current(code: String?, effectiveOnly: Boolean): List<ProductRevision>
    fun version(code: String, version: Long): ProductRevision?
    fun history(code: String): List<ProductRevision>
    fun create(product: Product, editor: CatalogEditor, reason: String): ProductRevision
    fun replace(product: Product, expectedVersion: Long, editor: CatalogEditor, reason: String): ProductRevision
}

class CatalogUseCases(
    private val reader: CatalogReader,
    private val writer: CatalogWriter,
    private val authority: CatalogAuthority,
    private val clock: Clock,
) : Catalog {
    override fun snapshotForSubmission(selectedProductCode: String): SubmissionProductSnapshot? {
        checkCode(selectedProductCode)
        authority.requireCurrent()
        return reader.snapshotForSubmission(selectedProductCode)
    }

    override fun current(code: String?, effectiveOnly: Boolean): List<ProductRevision> {
        code?.let(::checkCode)
        authority.requireCurrent()
        return reader.current(code, if (effectiveOnly) clock.instant() else null)
    }

    override fun version(code: String, version: Long): ProductRevision? {
        checkCode(code)
        require(version > 0) { "Некорректная редакция продукта" }
        return reader.version(code, version)
    }

    override fun history(code: String): List<ProductRevision> {
        checkCode(code)
        return reader.history(code)
    }

    override fun create(product: Product, editor: CatalogEditor, reason: String): ProductRevision {
        validate(product)
        require(product.version == 1L && product.lifecycleStatus == ProductLifecycleStatus.DRAFT) {
            "Новый продукт должен быть черновиком первой версии"
        }
        return writer.create(product, editor, validReason(reason), clock.instant())
    }

    override fun replace(product: Product, expectedVersion: Long, editor: CatalogEditor, reason: String): ProductRevision {
        validate(product)
        require(expectedVersion in 1 until Long.MAX_VALUE && expectedVersion == product.version) { "Укажите текущую версию продукта" }
        return writer.replace(product, expectedVersion, editor, validReason(reason), clock.instant())
    }

    private fun validate(product: Product) {
        checkCode(product.code)
        val problems = product.problems()
        require(problems.isEmpty()) { problems.joinToString("; ") }
    }

    private fun checkCode(code: String) {
        require(validProductCode(code)) { "Некорректный код продукта" }
    }

    private fun validReason(reason: String): String {
        require(reason.isNotBlank() && reason.length <= 1000) { "Опишите причину изменения — до 1000 символов" }
        return reason.trim()
    }
}
