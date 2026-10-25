package ru.creditcabinet.advice

import ru.creditcabinet.advice.domain.AdviceKind
import ru.creditcabinet.advice.domain.MivarModel
import ru.creditcabinet.advice.domain.MivarRule
import ru.creditcabinet.advice.domain.P
import ru.creditcabinet.advice.domain.UnderwritingAdviceModel
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UnderwritingAdviceTest {
    private val sum = MivarRule("SUM", "Сумма", listOf("a", "b"), listOf("c")) { mapOf("c" to (it["a"] as Int) + (it["b"] as Int)) }
    private val double = MivarRule("DOUBLE", "Удвоение", listOf("c"), listOf("d")) { mapOf("d" to (it["c"] as Int) * 2) }

    @Test
    fun `rules fire in the order the data allows, not the order they are written`() {
        val inference = MivarModel(1, listOf(double, sum)).infer(mapOf("a" to 1, "b" to 2))
        assertEquals(6, inference.known["d"])
        assertEquals(listOf("SUM", "DOUBLE"), inference.chain("d").map { it.ruleId })
    }

    @Test
    fun `a rule without all its inputs does not fire, and a parameter has one producer`() {
        assertEquals(null, MivarModel(1, listOf(sum, double)).infer(mapOf("a" to 1)).known["d"])
        assertFailsWith<IllegalArgumentException> { MivarModel(1, listOf(sum, MivarRule("SUM2", "Ещё", listOf("a"), listOf("c")) { mapOf("c" to 0) })) }
    }

    private fun facts(vararg overrides: Pair<String, Any?>): Map<String, Any?> =
        mapOf(
            P.AMOUNT to 5_000_000L, P.TERM to 12, P.BASE_RATE to BigDecimal("20"), P.MAX_TERM to 36, P.MIN_AMOUNT to 300_000L,
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
}
