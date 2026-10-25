package ru.creditcabinet.advice

import ru.creditcabinet.advice.domain.AdviceKind
import ru.creditcabinet.advice.domain.NetworkRule
import ru.creditcabinet.advice.domain.P
import ru.creditcabinet.advice.domain.RuleNetwork
import ru.creditcabinet.advice.domain.UnderwritingAdviceModel
import ru.creditcabinet.product.domain.LoanMath
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UnderwritingAdviceTest {
    private val sum = NetworkRule("SUM", "Сумма", listOf("a", "b"), listOf("c")) { mapOf("c" to (it["a"] as Int) + (it["b"] as Int)) }
    private val double = NetworkRule("DOUBLE", "Удвоение", listOf("c"), listOf("d")) { mapOf("d" to (it["c"] as Int) * 2) }

    @Test
    fun `rules fire in the order the data allows, not the order they are written`() {
        val inference = RuleNetwork(1, listOf(double, sum)).infer(mapOf("a" to 1, "b" to 2))
        assertEquals(6, inference.known["d"])
        assertEquals(listOf("SUM", "DOUBLE"), inference.chain("d").map { it.ruleId })
    }

    @Test
    fun `a rule without all its inputs does not fire, and a parameter has one producer`() {
        assertEquals(null, RuleNetwork(1, listOf(sum, double)).infer(mapOf("a" to 1)).known["d"])
        assertFailsWith<IllegalArgumentException> { RuleNetwork(1, listOf(sum, NetworkRule("SUM2", "Ещё", listOf("a"), listOf("c")) { mapOf("c" to 0) })) }
    }

    private fun facts(vararg overrides: Pair<String, Any?>): Map<String, Any?> =
        mapOf(
            P.AMOUNT to 5_000_000L, P.TERM to 12, P.BASE_RATE to BigDecimal("20"), P.MAX_TERM to 36, P.MIN_AMOUNT to 300_000L,
            P.MAX_AMOUNT to 50_000_000L, P.REVOLVING to false,
            P.AUTO_LIMIT to 3_000_000L, P.ANNUAL_REVENUE to 24_000_000L, P.EXISTING_PAYMENTS to 250_000L, P.MAX_LOAD to 0.35,
            P.REVENUE_MISMATCH to 0.3, P.DECLARED_REVENUE to 24_000_000L, P.TAX_REVENUE to 24_000_000L, P.STOP_FACTORS to emptyList<String>(),
            P.SILENT_SOURCES to emptyList<String>(), P.BANK_CLIENT to true, P.TURNOVER_VARIATION to 0.1, P.INQUIRIES to 1, P.WORST_OVERDUE to 0,
            P.OVERDUE_NOW to 0, P.TAX_DEBT to 0L, P.AGE_MONTHS to 60L, P.INDUSTRY_RISK to "MEDIUM", P.RATING to "B", P.SCORE to 76,
        ) + overrides

    private fun codes(facts: Map<String, Any?>) = UnderwritingAdviceModel.advise(facts).first.associate { it.first.code to it.first.kind }

    @Test
    fun `a load over the policy limit gives the risk and terms that pass, with the chain behind them`() {
        val (items, _) = UnderwritingAdviceModel.advise(facts())
        val byCode = items.associateBy { it.first.code }
        assertEquals(AdviceKind.RISK, byCode.getValue("LOAD").first.kind)
        val option = byCode["TERM"] ?: byCode.getValue("AMOUNT")
        assertEquals(AdviceKind.OPTION, option.first.kind)
        assertTrue(byCode.getValue("LOAD").second.any { it.startsWith("Нагрузка: платежи по кредитам с новым") }, byCode.getValue("LOAD").second.toString())
        assertEquals(AdviceKind.INFO, byCode.getValue("AUTO_LIMIT").first.kind)
    }

    @Test
    fun `a sound application asks for nothing`() {
        val advice = codes(facts(P.AMOUNT to 1_000_000L))
        assertTrue(advice.keys.none { it in setOf("LOAD", "REVENUE", "NO_ACCOUNT", "STOP") }, advice.toString())
    }

    @Test
    fun `the client's words against the tax service, a missing account and a stop factor`() {
        val advice = codes(
            facts(
                P.AMOUNT to 1_000_000L,
                P.DECLARED_REVENUE to 40_000_000L,
                P.BANK_CLIENT to false,
                P.STOP_FACTORS to listOf("Компания ликвидируется"),
            ),
        )
        assertEquals(AdviceKind.ASK, advice["REVENUE"])
        assertEquals(AdviceKind.ASK, advice["NO_ACCOUNT"])
        assertEquals(AdviceKind.STOP, advice["STOP"])
        assertEquals(AdviceKind.STOP, UnderwritingAdviceModel.advise(facts(P.STOP_FACTORS to listOf("x"))).first.first().first.kind, "most serious first")
    }

    @Test
    fun `unknown sources leave their rules silent instead of guessing`() {
        val advice = codes(facts(P.AMOUNT to 1_000_000L, P.TAX_REVENUE to null, P.BANK_CLIENT to null, P.INQUIRIES to null))
        assertTrue("REVENUE" !in advice && "NO_ACCOUNT" !in advice && "SEARCH" !in advice, advice.toString())
    }

    @Test
    fun `overdraft and card payments and affordable amounts use the same rounded calculation as the policy`() {
        for (revolving in listOf(false, true)) {
            val (items, inference) = UnderwritingAdviceModel.advise(
                facts(
                    P.AMOUNT to 1_000_000L,
                    P.TERM to 12,
                    P.MAX_TERM to 12,
                    P.BASE_RATE to BigDecimal("19"),
                    P.REVOLVING to revolving,
                    P.ANNUAL_REVENUE to 3_600_000L,
                    P.EXISTING_PAYMENTS to 0L,
                ),
            )
            val payment = LoanMath.calculate(1_000_000L, BigDecimal("19"), 12, revolving).monthlyPayment
            assertEquals(payment, inference.known[P.NEW_PAYMENT])
            assertEquals(revolving, inference.known[P.LOAD_EXCEEDS])
            if (revolving) {
                assertEquals(BigDecimal("115833.33"), payment)
                assertEquals(900_000L, inference.known[P.AFFORDABLE])
                assertTrue(items.any { it.first.code == "LOAD" && it.first.kind == AdviceKind.RISK })
                assertTrue(items.any { it.first.code == "AMOUNT" && it.first.kind == AdviceKind.OPTION })
                assertTrue(items.first { it.first.code == "LOAD" }.second.any { "115 833,33" in it })
                assertTrue(items.first { it.first.code == "LOAD" }.first.title.contains("38,61%"))
                assertTrue(LoanMath.calculate(900_000, BigDecimal("19"), 12, true).monthlyPayment.toDouble() <= 105_000)
                assertTrue(LoanMath.calculate(910_000, BigDecimal("19"), 12, true).monthlyPayment.toDouble() > 105_000)
                assertTrue(items.none { it.first.code == "TERM" })
            }
        }
    }

    @Test
    fun `kopecks and both sides of the policy budget are retained for current and longer terms`() {
        for (revolving in listOf(false, true)) {
            for (rate in listOf("18", "19.5", "21", "23", "25")) {
                val payment = LoanMath.calculate(1_000_000, BigDecimal(rate), 12, revolving).monthlyPayment.toDouble()
                for (delta in listOf(-0.01, 0.0, 0.01)) {
                    val limit = (payment + delta) / 300_000
                    val (_, inference) = UnderwritingAdviceModel.advise(
                        facts(
                            P.AMOUNT to 1_000_000L,
                            P.TERM to 12,
                            P.MAX_TERM to 12,
                            P.REVOLVING to revolving,
                            P.BASE_RATE to BigDecimal(rate),
                            P.ANNUAL_REVENUE to 3_600_000L,
                            P.EXISTING_PAYMENTS to 0L,
                            P.MAX_LOAD to limit,
                        ),
                    )
                    val budget = limit * 300_000
                    assertEquals(payment > budget, inference.known[P.LOAD_EXCEEDS], "$revolving $rate $delta")
                    assertEquals(payment <= budget, inference.known[P.FITS_MAX_TERM])
                    val amount = inference.known.getValue(P.AFFORDABLE) as Long
                    assertTrue(amount in 0..50_000_000 && amount % 10_000 == 0L)
                    if (amount > 0) assertTrue(LoanMath.calculate(amount, BigDecimal(rate), 12, revolving).monthlyPayment.toDouble() <= budget)
                }
            }
        }
    }

    @Test
    fun `affordable options respect the product maximum minimum and exhausted budget`() {
        val (_, capped) = UnderwritingAdviceModel.advise(facts(P.MAX_AMOUNT to 310_000L))
        assertEquals(310_000L, capped.known[P.AFFORDABLE])
        val (items, exhausted) = UnderwritingAdviceModel.advise(facts(P.EXISTING_PAYMENTS to 10_000_000L))
        assertEquals(0L, exhausted.known[P.AFFORDABLE])
        assertEquals(AdviceKind.RISK, items.first { it.first.code == "AMOUNT" }.first.kind)
    }
}
