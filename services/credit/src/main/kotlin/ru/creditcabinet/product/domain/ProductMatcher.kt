package ru.creditcabinet.product.domain

import java.util.Locale

/** One product as an answer to the client's need, with plain reasons and what to change if it does not fit. */
data class ProductMatch(
    val product: Product,
    val fits: Boolean,
    val reasons: List<String>,
    /** What has to change for the product to fit; empty when it fits. */
    val adjustments: List<String>,
    /** Term used for the calculation: the requested one, or the nearest the product allows. */
    val termMonths: Int,
    val calculation: Calculation?,
)

/**
 * The needs wizard: products for a purpose, amount and term, by plain rules rather than a model.
 * Products for another purpose are left out; the rest are sorted with fitting ones first and,
 * among them, the lowest payment first.
 */
object ProductMatcher {
    fun match(products: List<Product>, purpose: FinancingPurpose, amount: Long, termMonths: Int): List<ProductMatch> =
        products
            .filter { purpose in it.purposes }
            .map { match(it, purpose, amount, termMonths) }
            .sortedWith(compareByDescending<ProductMatch> { it.fits }.thenBy { it.calculation?.monthlyPayment })

    private fun match(product: Product, purpose: FinancingPurpose, amount: Long, termMonths: Int): ProductMatch {
        val adjustments = buildList {
            if (amount < product.minAmount) add("Минимальная сумма — ${rubles(product.minAmount)}")
            if (amount > product.maxAmount) add("Максимальная сумма — ${rubles(product.maxAmount)}")
            if (!product.termFits(termMonths)) add(termAdjustment(product))
        }
        val term = product.nearestTerm(termMonths)
        val calcAmount = amount.coerceIn(product.minAmount, product.maxAmount)
        val reasons = buildList {
            add("Подходит для цели «${purpose.title.lowercase()}»")
            if (product.revolving) {
                add("Возобновляемый лимит: проценты начисляются только на использованную сумму")
            } else if (product.maxTermMonths >= LONG_TERM) {
                add("Срок до ${product.maxTermMonths} мес. позволяет снизить ежемесячный платёж")
            }
            if (product.code == "GOV_PROGRAM") add("Сниженная ставка, если отрасль компании входит в программу поддержки")
        }
        return ProductMatch(
            product = product,
            fits = adjustments.isEmpty(),
            reasons = reasons,
            adjustments = adjustments,
            termMonths = term,
            calculation = LoanMath.calculate(calcAmount, product.baseRate, term, product.revolving),
        )
    }

    private fun termAdjustment(product: Product) =
        if (product.minTermMonths == product.maxTermMonths) {
            "Лимит выдаётся на ${product.maxTermMonths} мес. и возобновляется"
        } else {
            "Срок — от ${product.minTermMonths} до ${product.maxTermMonths} мес."
        }

    private const val LONG_TERM = 36

    /** "1 000 000 ₽" with non-breaking spaces, the same on any server locale. */
    fun rubles(amount: Long): String = String.format(Locale.ROOT, "%,d", amount).replace(',', '\u00A0') + "\u00A0₽"
}
