package ru.creditcabinet.servicing

import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.servicing.domain.LoanAccount
import ru.creditcabinet.servicing.domain.LoanAccountStatus
import ru.creditcabinet.servicing.domain.LoanPaymentStatus
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoanAccountDomainTest {
    private val appId = UUID.randomUUID()
    private val companyId = UUID.randomUUID()
    private val disbursedAt = Instant.parse("2026-10-01T10:00:00Z")
    private val baseDate = LocalDate.of(2026, 10, 1)
    private val maturityDate = LocalDate.of(2027, 10, 1)

    @Test
    fun `open creates 50 percent initial tranche for revolving product and 100 percent for non-revolving`() {
        val revolving = LoanAccount.open(
            applicationId = appId,
            companyId = companyId,
            contractNumber = "KD-2026-0001",
            approvedLimit = 2_000_000L,
            termMonths = 12,
            rate = BigDecimal("19.00"),
            revolving = true,
            disbursedAt = disbursedAt,
            maturityDate = maturityDate,
            firstPaymentBaseDate = baseDate,
        )
        assertEquals(LoanAccountStatus.ACTIVE, revolving.status)
        assertEquals(1_000_000L, revolving.disbursedAmount)
        assertEquals(1_000_000L, revolving.availableLimit)
        assertEquals(BigDecimal("1000000.00"), revolving.outstandingPrincipal)
        assertEquals(1, revolving.tranches.size)
        assertEquals(1_000_000L, revolving.tranches.single().amount)
        assertEquals(12, revolving.payments.size)
        assertTrue(revolving.payments.all { it.status == LoanPaymentStatus.SCHEDULED })
        assertEquals(BigDecimal("0.00"), revolving.payments.last().remainingBalance)

        val standard = LoanAccount.open(
            applicationId = UUID.randomUUID(),
            companyId = companyId,
            contractNumber = "KD-2026-0002",
            approvedLimit = 3_000_000L,
            termMonths = 12,
            rate = BigDecimal("20.00"),
            revolving = false,
            disbursedAt = disbursedAt,
            maturityDate = maturityDate,
            firstPaymentBaseDate = baseDate,
        )
        assertEquals(3_000_000L, standard.disbursedAmount)
        assertEquals(0L, standard.availableLimit)
        assertEquals(BigDecimal("3000000.00"), standard.outstandingPrincipal)
        assertEquals(1, standard.tranches.size)
        assertEquals(3_000_000L, standard.tranches.single().amount)
        assertEquals(12, standard.payments.size)
    }

    @Test
    fun `drawTranche increases disbursed amount and recalculates remaining scheduled payments`() {
        val opened = LoanAccount.open(
            applicationId = appId,
            companyId = companyId,
            contractNumber = "KD-2026-0003",
            approvedLimit = 2_000_000L,
            termMonths = 6,
            rate = BigDecimal("19.00"),
            revolving = true,
            disbursedAt = disbursedAt,
            maturityDate = baseDate.plusMonths(6),
            firstPaymentBaseDate = baseDate,
        )
        val afterPay1 = opened.payNext(opened.payments.first().id, disbursedAt.plusSeconds(86400))
        val remainingBefore = afterPay1.outstandingPrincipal
        val afterTranche2 = afterPay1.drawTranche(
            amount = 500_000L,
            purpose = "Закупка партии",
            now = disbursedAt.plusSeconds(172800),
            today = baseDate.plusDays(2),
        )

        assertEquals(1_500_000L, afterTranche2.disbursedAmount)
        assertEquals(500_000L, afterTranche2.availableLimit)
        assertEquals(remainingBefore.add(BigDecimal("500000.00")), afterTranche2.outstandingPrincipal)
        assertEquals(2, afterTranche2.tranches.size)
        assertEquals(2, afterTranche2.tranches.last().trancheNumber)
        assertEquals("Закупка партии", afterTranche2.tranches.last().purpose)

        val paid = afterTranche2.payments.filter { it.status == LoanPaymentStatus.PAID }
        val scheduled = afterTranche2.payments.filter { it.status == LoanPaymentStatus.SCHEDULED }
        assertEquals(1, paid.size)
        assertEquals(5, scheduled.size)
        val sumScheduledPrincipal = scheduled.fold(BigDecimal.ZERO) { acc, p -> acc.add(p.principalAmount) }
        assertEquals(afterTranche2.outstandingPrincipal, sumScheduledPrincipal)
        assertEquals(BigDecimal("0.00"), scheduled.last().remainingBalance)
    }

    @Test
    fun `drawTranche preserves due amounts and refuses the maturity boundary`() {
        val loan = LoanAccount.open(
            applicationId = appId,
            companyId = companyId,
            contractNumber = "KD-2026-0007",
            approvedLimit = 2_000_000L,
            termMonths = 12,
            rate = BigDecimal("18.00"),
            revolving = true,
            disbursedAt = disbursedAt,
            maturityDate = maturityDate,
            firstPaymentBaseDate = baseDate,
        )
        val today = baseDate.plusMonths(2)
        val now = disbursedAt.plusSeconds(60L * 86400)
        val updated = loan.drawTranche(250_000L, null, now, today)
        assertEquals(loan.payments.take(2), updated.payments.take(2))
        assertEquals(loan.payments.map { it.dueDate }, updated.payments.map { it.dueDate })
        assertEquals(updated.outstandingPrincipal, updated.payments.sumOf { it.principalAmount })
        assertTrue(updated.payments[2].interestAmount > loan.payments[2].interestAmount)
        assertEquals(2, loan.drawTranche(1L, null, now, maturityDate.minusDays(1)).tranches.size)
        for (day in listOf(maturityDate, maturityDate.plusDays(1))) {
            assertFailsWith<ConflictException> { loan.drawTranche(1L, null, now, day) }
        }
    }

    @Test
    fun `drawTranche rejects invalid amount, non-revolving, exceeding limit, and repaid loans`() {
        val revolving = LoanAccount.open(
            applicationId = appId,
            companyId = companyId,
            contractNumber = "KD-2026-0004",
            approvedLimit = 2_000_000L,
            termMonths = 6,
            rate = BigDecimal("19.00"),
            revolving = true,
            disbursedAt = disbursedAt,
            maturityDate = baseDate.plusMonths(6),
            firstPaymentBaseDate = baseDate,
        )
        assertFailsWith<BadRequestException> {
            revolving.drawTranche(0L, "zero", disbursedAt, baseDate)
        }
        assertFailsWith<BadRequestException> {
            revolving.drawTranche(-100L, "negative", disbursedAt, baseDate)
        }
        assertFailsWith<ConflictException> {
            revolving.drawTranche(1_000_001L, "over limit", disbursedAt, baseDate)
        }

        val repaid = revolving.repayEarly(disbursedAt.plusSeconds(3600), baseDate)
        assertEquals(LoanAccountStatus.REPAID, repaid.status)
        assertEquals(0L, repaid.availableLimit)
        assertFailsWith<ConflictException> {
            repaid.drawTranche(100_000L, "after repaid", disbursedAt.plusSeconds(7200), baseDate)
        }

        val standard = LoanAccount.open(
            applicationId = UUID.randomUUID(),
            companyId = companyId,
            contractNumber = "KD-2026-0005",
            approvedLimit = 1_000_000L,
            termMonths = 6,
            rate = BigDecimal("20.00"),
            revolving = false,
            disbursedAt = disbursedAt,
            maturityDate = baseDate.plusMonths(6),
            firstPaymentBaseDate = baseDate,
        )
        assertFailsWith<ConflictException> {
            standard.drawTranche(100_000L, "non-revolving", disbursedAt, baseDate)
        }
    }

    @Test
    fun `payNext sequentially closes loan on final payment and blocks further payments`() {
        var account = LoanAccount.open(
            applicationId = appId,
            companyId = companyId,
            contractNumber = "KD-2026-0006",
            approvedLimit = 600_000L,
            termMonths = 3,
            rate = BigDecimal("18.00"),
            revolving = false,
            disbursedAt = disbursedAt,
            maturityDate = baseDate.plusMonths(3),
            firstPaymentBaseDate = baseDate,
        )

        val first = account.payments[0].id
        account = account.payNext(first, disbursedAt.plusSeconds(10))
        assertEquals(LoanAccountStatus.ACTIVE, account.status)
        assertNull(account.repaidAt)
        assertEquals(1, account.payments.count { it.status == LoanPaymentStatus.PAID })

        assertFailsWith<ConflictException> { account.payNext(first, disbursedAt.plusSeconds(20)) }
        account = account.payNext(account.payments[1].id, disbursedAt.plusSeconds(20))
        assertEquals(LoanAccountStatus.ACTIVE, account.status)
        assertEquals(2, account.payments.count { it.status == LoanPaymentStatus.PAID })

        account = account.payNext(account.payments[2].id, disbursedAt.plusSeconds(30))
        assertEquals(LoanAccountStatus.REPAID, account.status)
        assertNotNull(account.repaidAt)
        assertEquals(BigDecimal("0.00"), account.outstandingPrincipal)
        assertEquals(3, account.payments.count { it.status == LoanPaymentStatus.PAID })

        assertFailsWith<ConflictException> {
            account.payNext(first, disbursedAt.plusSeconds(40))
        }
        assertFailsWith<ConflictException> {
            account.repayEarly(disbursedAt.plusSeconds(40), baseDate)
        }
    }
}
