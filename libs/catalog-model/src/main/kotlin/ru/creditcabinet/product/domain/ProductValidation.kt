package ru.creditcabinet.product.domain

private val productCodePattern = Regex("[A-Z0-9_]{1,32}")

fun validProductCode(code: String): Boolean = productCodePattern.matches(code)

/** Invalid edits cannot enter a historical snapshot used for lending. */
fun Product.problems(): List<String> = buildList {
    if (name.isBlank() || name.length > 255) add("Название — от 1 до 255 символов")
    if (description.isBlank() || description.length > 5000) add("Описание — от 1 до 5000 символов")
    if (purposes.isEmpty()) add("Выберите хотя бы одну цель")
    if (minAmount <= 0 || minAmount > maxAmount) add("Минимальная сумма должна быть больше нуля и не больше максимальной")
    if (minTermMonths <= 0 || minTermMonths > maxTermMonths || maxTermMonths > 120) add("Срок — от 1 до 120 месяцев, минимум не больше максимума")
    if (baseRate.signum() <= 0 || baseRate > 100.toBigDecimal() || baseRate.scale() > 2) add("Ставка — больше 0 и не больше 100%, до двух знаков после запятой")
    if (autoApprovalLimit !in minAmount..maxAmount) add("Лимит автоодобрения должен быть в пределах сумм продукта")
    if (effectiveFrom != null && effectiveTo != null && effectiveFrom.isAfter(effectiveTo)) {
        add("Дата начала действия не может быть позже даты окончания")
    }
    if (agentCommissionRateBps !in 0..1000) add("Комиссия агента — от 0 до 1000 б.п.")
}
