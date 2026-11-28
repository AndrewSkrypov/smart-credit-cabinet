package ru.creditcabinet.servicing.paymentreserve

import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import ru.creditcabinet.agent.application.AgentApplicationService
import ru.creditcabinet.application.application.CreditCaller
import ru.creditcabinet.application.application.CreditFailure.NotFound
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.servicing.contract.v1.ConfirmReserveCommand
import ru.creditcabinet.servicing.contract.v1.ReceiptCommand
import ru.creditcabinet.servicing.contract.v1.ReserveCommand
import ru.creditcabinet.servicing.contract.v1.ServicingActor
import ru.creditcabinet.servicing.contract.v1.ServicingOperations
import ru.creditcabinet.servicing.domain.LoanAccount
import ru.creditcabinet.servicing.domain.LoanAccountStatus
import ru.creditcabinet.servicing.paymentreserve.application.PaymentReserveService
import ru.creditcabinet.servicing.paymentreserve.domain.AgentPaymentReserveSummary
import ru.creditcabinet.servicing.paymentreserve.domain.AgentReserveAction
import ru.creditcabinet.servicing.paymentreserve.domain.PaymentReserveInputs
import ru.creditcabinet.servicing.paymentreserve.domain.PaymentReserveQuote
import ru.creditcabinet.servicing.paymentreserve.domain.ReceiptEvent
import ru.creditcabinet.servicing.paymentreserve.domain.ReceiptStatus
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class PaymentReserveDevAccessTest {
    @Test
    fun `disabled permission refuses every reserve operation before accessing any port`() {
        val fixture = Fixture()
        val service = fixture.service(enabled = false)
        assertFailsWith<NotFound> { service.get(fixture.loanId) }
        assertFailsWith<NotFound> { service.put(fixture.loanId, fixture.inputs) }
        assertFailsWith<NotFound> { service.receipt(fixture.loanId, fixture.event) }
        assertFailsWith<NotFound> { service.confirm(fixture.loanId, fixture.expectedVersion) }
        assertFailsWith<NotFound> { service.agentSummary(fixture.applicationId) }
        verifyNoInteractions(fixture.operations, fixture.applications, fixture.agentApplications, fixture.caller)
    }

    @Test
    fun `enabled permission delegates exact owner inputs event actor and confirmation version`() {
        val fixture = Fixture().also { it.stub() }
        val service = fixture.service()
        assertSame(fixture.quote, service.get(fixture.loanId))
        assertSame(fixture.quote, service.put(fixture.loanId, fixture.inputs))
        assertSame(fixture.quote, service.receipt(fixture.loanId, fixture.event))
        assertSame(fixture.quote, service.confirm(fixture.loanId, fixture.expectedVersion))

        verify(fixture.operations).reserve(fixture.loanId)
        verify(fixture.operations).putReserve(fixture.loanId, ReserveCommand(fixture.actor, fixture.inputs))
        verify(fixture.operations).receipt(fixture.loanId, ReceiptCommand(fixture.actor, fixture.event))
        verify(fixture.operations).confirmReserve(fixture.loanId, ConfirmReserveCommand(fixture.actor, fixture.expectedVersion))
        assertNoDirectLoanMutation(fixture.operations)
    }

    @Test
    fun `inconsistent aggregate identities cannot authorize reserve reads or confirmation`() {
        val cases: List<Pair<String, (Fixture) -> Unit>> = listOf(
            "different account id" to { fixture: Fixture -> fixture.account = fixture.account.copy(id = UUID.randomUUID()) },
            "different application id" to { fixture: Fixture -> fixture.application = fixture.application.copy(id = UUID.randomUUID()) },
            "different application company" to { fixture: Fixture ->
                fixture.application = fixture.application.copy(company = fixture.application.company.copy(id = UUID.randomUUID()))
            },
        )
        for ((label, change) in cases) {
            val fixture = Fixture()
            change(fixture)
            fixture.stub()
            val service = fixture.service()
            assertFailsWith<NotFound>(label) { service.get(fixture.loanId) }
            assertFailsWith<NotFound>(label) { service.confirm(fixture.loanId, fixture.expectedVersion) }
            verify(fixture.operations, never()).reserve(any())
            verify(fixture.operations, never()).putReserve(any(), any())
            verify(fixture.operations, never()).receipt(any(), any())
            verify(fixture.operations, never()).confirmReserve(any(), any())
            assertNoDirectLoanMutation(fixture.operations)
        }
    }

    @Test
    fun `a repaid loan still delegates saved quote reads and identical put and confirmation replays to its owner`() {
        val fixture = Fixture()
        fixture.account = fixture.account.copy(status = LoanAccountStatus.REPAID)
        fixture.quote = fixture.quote.copy(version = 8, receiptStatus = ReceiptStatus.DELAYED, drawnAmount = 130_000)
        fixture.stub()
        val service = fixture.service()

        assertSame(fixture.quote, service.get(fixture.loanId))
        assertSame(fixture.quote, service.put(fixture.loanId, fixture.inputs))
        repeat(2) {
            assertSame(fixture.quote, service.confirm(fixture.loanId, fixture.expectedVersion))
        }

        verify(fixture.operations).reserve(fixture.loanId)
        verify(fixture.operations).putReserve(fixture.loanId, ReserveCommand(fixture.actor, fixture.inputs))
        verify(fixture.operations, times(2)).confirmReserve(
            fixture.loanId,
            ConfirmReserveCommand(fixture.actor, fixture.expectedVersion),
        )
        verify(fixture.operations, never()).receipt(any(), any())
        assertNoDirectLoanMutation(fixture.operations)
    }

    @Test
    fun `an authorized agent summary uses its existing RPC without an extra account lookup`() {
        val fixture = Fixture().also { it.stub() }
        assertSame(fixture.summary, fixture.service().agentSummary(fixture.applicationId))
        verify(fixture.agentApplications).ownAgentApplication(fixture.applicationId)
        verify(fixture.operations).agentSummary(fixture.applicationId)
        verifyNoMoreInteractions(fixture.operations)
        verifyNoInteractions(fixture.caller)
    }

    @Test
    fun `a revoked agent mandate fails before any Servicing lookup or summary`() {
        val fixture = Fixture().also { it.stub() }
        whenever(fixture.agentApplications.ownAgentApplication(fixture.applicationId)).thenThrow(NotFound("Synthetic revoked mandate"))
        assertFailsWith<NotFound> { fixture.service().agentSummary(fixture.applicationId) }
        verifyNoInteractions(fixture.operations, fixture.applications, fixture.caller)
    }

    private fun assertNoDirectLoanMutation(operations: ServicingOperations) {
        verify(operations, never()).open(any())
        verify(operations, never()).draw(any(), any())
        verify(operations, never()).payNext(any(), any())
        verify(operations, never()).repayEarly(any(), any())
    }

    private class Fixture {
        val operations = mock<ServicingOperations>()
        val applications = mock<ApplicationRepository>()
        val agentApplications = mock<AgentApplicationService>()
        val caller = mock<CreditCaller>()
        private val now = Instant.parse("2026-11-27T10:00:00Z")
        private val today = LocalDate.parse("2026-11-27")
        private val clock = Clock.fixed(now, ZoneOffset.UTC)
        private val companyId = UUID.randomUUID()
        private val userId = UUID.randomUUID()
        private val client = TokenClaims(userId, Roles.CLIENT, "Synthetic Client", "client@example.invalid", companyId)
        private val agent = TokenClaims(UUID.randomUUID(), Roles.AGENT, "Synthetic Agent", "agent@example.invalid")
        val actor = ServicingActor(userId, companyId)
        var application = LoanApplication(
            company = CompanySnapshot(companyId, "0012345678", "Synthetic company", LegalForm.LEGAL_ENTITY),
            createdBy = userId,
            terms = ApplicationTerms("OVERDRAFT", FinancingPurpose.CASH_GAP, amount = 400_000, termMonths = 12),
            status = ApplicationStatus.DISBURSED,
            createdAt = now,
        )
        val applicationId: UUID = application.id
        var account = LoanAccount.open(
            applicationId,
            companyId,
            "SYNTHETIC-RESERVE",
            400_000,
            12,
            BigDecimal("19.00"),
            true,
            now,
            today.plusYears(1),
            today,
        )
        val loanId: UUID = account.id
        val inputs = PaymentReserveInputs(1_900_000, 140_000, 2_000_000, 30_000, today, today.plusDays(7))
        val event = ReceiptEvent("synthetic-receipt-1", ReceiptStatus.DELAYED)
        val expectedVersion = 7L
        var quote = PaymentReserveQuote(
            expectedVersion,
            inputs,
            ReceiptStatus.PENDING,
            0,
            200_000,
            130_000,
            0,
            0,
            -100_000,
            false,
            0,
        )
        val summary = AgentPaymentReserveSummary(expectedVersion, ReceiptStatus.PENDING, inputs.paymentDate, AgentReserveAction.WAIT_FOR_RECEIPT)

        fun stub() {
            whenever(caller.requireClient()).thenReturn(client to companyId)
            whenever(applications.find(account.applicationId)).thenReturn(application)
            whenever(agentApplications.ownAgentApplication(applicationId)).thenReturn(agent to application)
            whenever(operations.byId(loanId)).thenReturn(account)
            whenever(operations.reserve(loanId)).thenReturn(quote)
            whenever(operations.putReserve(loanId, ReserveCommand(actor, inputs))).thenReturn(quote)
            whenever(operations.receipt(loanId, ReceiptCommand(actor, event))).thenReturn(quote)
            whenever(operations.confirmReserve(loanId, ConfirmReserveCommand(actor, expectedVersion))).thenReturn(quote)
            whenever(operations.agentSummary(applicationId)).thenReturn(summary)
        }

        fun service(enabled: Boolean = true) = PaymentReserveService(
            operations,
            applications,
            agentApplications,
            clock,
            enabled,
            caller,
        )
    }
}
