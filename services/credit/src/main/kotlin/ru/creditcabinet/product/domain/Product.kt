package ru.creditcabinet.product.domain

import java.math.BigDecimal

/** What the business needs the money for; the client picks one in the needs wizard. */
enum class FinancingPurpose(val title: String) {
    CASH_GAP("Покрыть кассовый разрыв"),
    CURRENT_EXPENSES("Текущие расходы"),
    WORKING_CAPITAL("Закупка товара или сырья"),
    EQUIPMENT("Покупка оборудования"),
    VEHICLE("Покупка транспорта"),
    RENOVATION("Ремонт помещения"),
    BUSINESS_DEVELOPMENT("Развитие или новое направление"),
}

data class Product(
    val code: String,
    val name: String,
    val description: String,
    val purposes: Set<FinancingPurpose>,
    val minAmount: Long,
    val maxAmount: Long,
    val minTermMonths: Int,
    val maxTermMonths: Int,
    /** Annual rate in percent before the rating margin. */
    val baseRate: BigDecimal,
    /** Above this amount an application always goes to an underwriter. */
    val autoApprovalLimit: Long,
    /** A limit used and repaid again (overdraft, card) rather than a one-off loan. */
    val revolving: Boolean,
    val version: Long = 1,
    val active: Boolean = true,
) {
    fun amountFits(amount: Long) = amount in minAmount..maxAmount

    fun termFits(termMonths: Int) = termMonths in minTermMonths..maxTermMonths

    /** The term closest to [termMonths] the product allows. */
    fun nearestTerm(termMonths: Int) = termMonths.coerceIn(minTermMonths, maxTermMonths)

    companion object {
        const val INVESTMENT = "INVESTMENT"
    }
}

interface ProductCatalog {
    fun all(): List<Product>
    fun find(code: String): Product?
    fun version(code: String, version: Long): Product?
    fun latest(code: String): Product?
}
