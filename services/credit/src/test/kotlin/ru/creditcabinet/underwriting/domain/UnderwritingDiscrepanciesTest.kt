package ru.creditcabinet.underwriting.domain

import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.assessment.domain.BureauInfo
import ru.creditcabinet.assessment.domain.RegistryInfo
import ru.creditcabinet.assessment.domain.TaxInfo
import ru.creditcabinet.product.domain.FinancingPurpose
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UnderwritingDiscrepanciesTest {
    private val terms = ApplicationTerms("WORKING_CAPITAL", FinancingPurpose.WORKING_CAPITAL, amount = 1_000_000, termMonths = 12)
    private val tax = TaxInfo(true, 2025, 1_000_000, null, 0, null, null, false)
    private val bureau = BureauInfo(emptyList(), 100_000, 0, 0, 0)
    private val company = RegistryInfo(
        inn = "0001001039",
        ogrn = "0000000000000",
        fullName = "ООО Тест",
        shortName = "Тест",
        status = "ACTIVE",
        statusTitle = "действует",
        registrationDate = LocalDate.of(2020, 1, 1),
        okvedCode = "62.01",
        okvedName = "Разработка ПО",
        directorName = "Синтетический руководитель",
        address = "Тестовый адрес",
        unreliableAddress = false,
        unreliableDirector = false,
    )

    @Test
    fun `material differences keep the declared and source values separate`() {
        val declared = terms.copy(declaredRevenue12m = 1_200_000, declaredMonthlyDebtPayments = 125_000)
        val registry = company.copy(status = "LIQUIDATING", statusTitle = "ликвидируется")
        val result = UnderwritingDiscrepancies.compare(declared, tax, bureau, registry)

        assertEquals(
            listOf(
                Discrepancy("Выручка за год", "1\u00A0200\u00A0000\u00A0₽", "ФНС", "1\u00A0000\u00A0000\u00A0₽", 20),
                Discrepancy("Платежи по кредитам в месяц", "125\u00A0000\u00A0₽", "БКИ", "100\u00A0000\u00A0₽", null),
                Discrepancy("Статус компании", "действует", "ЕГРЮЛ/ЕГРИП", "ликвидируется", null),
            ),
            result,
        )
    }

    @Test
    fun `revenue and debt at their tolerance boundaries do not become discrepancies`() {
        val declared = terms.copy(declaredRevenue12m = 1_050_000, declaredMonthlyDebtPayments = 110_000)

        assertTrue(UnderwritingDiscrepancies.compare(declared, tax, bureau, company).isEmpty())
    }

    @Test
    fun `absent sources and zero reported revenue do not produce a misleading percentage`() {
        val declared = terms.copy(declaredRevenue12m = 1_200_000, declaredMonthlyDebtPayments = 125_000)

        assertTrue(UnderwritingDiscrepancies.compare(declared, tax.copy(revenue = 0), null, null).isEmpty())
        assertTrue(UnderwritingDiscrepancies.compare(terms, tax, bureau, null).isEmpty())
    }
}
