package ru.creditcabinet.product

import ru.creditcabinet.product.domain.LoanMath
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

class LoanMathTest {
    @Test
    fun `annuity payment matches the textbook value`() {
        // 1 000 000 at 12% a year for 12 months: 88 848.79 a month.
        assertEquals(BigDecimal("88848.79"), LoanMath.annuityPayment(1_000_000, BigDecimal("12"), 12))
    }

    @Test
    fun `the schedule repays exactly the principal and ends at zero`() {
        val calculation = LoanMath.calculate(1_000_000, BigDecimal("20"), 24, revolving = false)
        assertEquals(24, calculation.schedule.size)
        assertEquals(0, calculation.schedule.last().balance.signum())
        val principal = calculation.schedule.fold(BigDecimal.ZERO) { sum, row -> sum.add(row.principal) }
        assertEquals(0, principal.compareTo(BigDecimal(1_000_000)))
        assertEquals(0, calculation.overpayment.compareTo(calculation.totalPayment.subtract(BigDecimal(1_000_000))))
    }

    @Test
    fun `a revolving limit starts with interest plus a tenth of the debt`() {
        val calculation = LoanMath.calculate(1_000_000, BigDecimal("19"), 12, revolving = true)
        // 1 000 000 * 19% / 12 = 15 833.33 interest + 100 000 principal.
        assertEquals(BigDecimal("115833.33"), calculation.monthlyPayment)
        assertEquals(0, calculation.schedule.last().balance.signum())
    }
}
