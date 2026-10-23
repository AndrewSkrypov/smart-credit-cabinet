package ru.creditcabinet.product.domain

import java.time.Instant
import java.util.UUID

data class ProductRevision(val product: Product, val authorId: UUID?, val reason: String, val createdAt: Instant)

interface ProductVersions {
    fun list(): List<Product>
    fun history(code: String): List<ProductRevision>
    fun replace(product: Product, expectedVersion: Long, authorId: UUID, reason: String, now: Instant): ProductRevision
}

/** Invalid edits cannot enter a historical snapshot used for lending. */
fun Product.problems(): List<String> = buildList {
    if (name.isBlank() || name.length > 255) add("Название — от 1 до 255 символов")
    if (description.isBlank() || description.length > 5000) add("Описание — от 1 до 5000 символов")
    if (purposes.isEmpty()) add("Выберите хотя бы одну цель")
    if (minAmount <= 0 || minAmount > maxAmount) add("Минимальная сумма должна быть больше нуля и не больше максимальной")
    if (minTermMonths <= 0 || minTermMonths > maxTermMonths || maxTermMonths > 120) add("Срок — от 1 до 120 месяцев, минимум не больше максимума")
    if (baseRate.signum() <= 0 || baseRate > 100.toBigDecimal() || baseRate.scale() > 2) add("Ставка — больше 0 и не больше 100%, до двух знаков после запятой")
    if (autoApprovalLimit !in minAmount..maxAmount) add("Лимит автоодобрения должен быть в пределах сумм продукта")
}
