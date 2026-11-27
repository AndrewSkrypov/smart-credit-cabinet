package ru.creditcabinet.servicing.infrastructure

import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import ru.creditcabinet.application.application.CreditCaller
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.service.ApplicationService
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.servicing.application.LoanServicingService
import ru.creditcabinet.servicing.contract.v1.ServicingOperations
import ru.creditcabinet.servicing.domain.LoanAccount
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LoanServicingVisibilityTest {
    @Test fun `pending Credit acknowledgment cannot expose an already opened target account as issued`() {
        val app = LoanApplication(
            company = CompanySnapshot(UUID.randomUUID(), "0012345678", "Synthetic", LegalForm.LEGAL_ENTITY),
            createdBy = UUID.randomUUID(),
            terms = ApplicationTerms("OVERDRAFT", FinancingPurpose.CASH_GAP, amount = 1_000_000, termMonths = 12),
            status = ApplicationStatus.DISBURSING,
            createdAt = Instant.parse("2026-10-01T10:00:00Z"),
        )
        val operations = mock<ServicingOperations>()
        val applications = mock<ApplicationService>()
        whenever(applications.get(app.id)).thenReturn(app)
        val facade = LoanServicingService(operations, applications, mock<CreditCaller>()) { _, _ -> error("unused") }
        assertFailsWith<NotFoundException> { facade.getByApplicationId(app.id) }
        verifyNoInteractions(operations)
        val account = LoanAccount.open(
            app.id, app.company.id, "C-1", 1_000_000, 12, BigDecimal("20.00"), true,
            app.createdAt, LocalDate.parse("2027-10-01"), LocalDate.parse("2026-10-01"),
        )
        whenever(applications.get(app.id)).thenReturn(app.copy(status = ApplicationStatus.DISBURSED))
        whenever(operations.byApplication(app.id)).thenReturn(account)
        assertEquals(account, facade.getByApplicationId(app.id))
    }
}
