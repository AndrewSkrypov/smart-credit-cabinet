package ru.creditcabinet.product

import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductMatcher
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProductMatcherTest {
    private val overdraft = Product(
        "OVERDRAFT", "Овердрафт", "", setOf(FinancingPurpose.CASH_GAP), 100_000, 5_000_000, 12, 12, BigDecimal("19"), 2_000_000, true,
    )
    private val investment = Product(
        "INVESTMENT", "Инвестиционный кредит", "", setOf(FinancingPurpose.EQUIPMENT), 1_000_000, 50_000_000, 12, 60, BigDecimal("18"), 5_000_000, false,
    )
    private val gov = Product(
        "GOV_PROGRAM", "Госпрограмма", "", setOf(FinancingPurpose.EQUIPMENT), 500_000, 30_000_000, 12, 36, BigDecimal("12"), 3_000_000, false,
    )
    private val all = listOf(overdraft, investment, gov)

    @Test
    fun `only products for the purpose are offered, fitting ones with the lower payment first`() {
        val matches = ProductMatcher.match(all, FinancingPurpose.EQUIPMENT, 2_000_000, 36)
        assertEquals(listOf("GOV_PROGRAM", "INVESTMENT"), matches.map { it.product.code })
        assertTrue(matches.all { it.fits })
    }

    @Test
    fun `a product that does not fit says what to change and is still calculated within its limits`() {
        val match = ProductMatcher.match(all, FinancingPurpose.EQUIPMENT, 700_000, 36).first { it.product.code == "INVESTMENT" }
        assertFalse(match.fits)
        assertEquals(listOf("Минимальная сумма — 1\u00A0000\u00A0000\u00A0₽"), match.adjustments)
        assertTrue(match.calculation != null)
    }

    @Test
    fun `a term outside the product is replaced by the nearest allowed one`() {
        val match = ProductMatcher.match(all, FinancingPurpose.CASH_GAP, 500_000, 6).single()
        assertEquals(12, match.termMonths)
        assertEquals(listOf("Лимит выдаётся на 12 мес. и возобновляется"), match.adjustments)
    }
}
