package ru.creditcabinet.application.domain

import ru.creditcabinet.application.service.canSee
import ru.creditcabinet.application.service.toActor
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.Product
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentApplicationDomainTest {
    private val now = Instant.parse("2026-11-25T10:00:00Z")
    private val companyId = UUID.randomUUID()
    private val clientUserId = UUID.randomUUID()
    private val agentUserId = UUID.randomUUID()
    private val otherAgentId = UUID.randomUUID()
    private val clientActor = Actor(ActorType.CLIENT, clientUserId)
    private val agentActor = Actor(ActorType.AGENT, agentUserId)
    private val otherAgentActor = Actor(ActorType.AGENT, otherAgentId)

    private val product = Product(
        code = "WORKING_CAPITAL",
        name = "Оборотный кредит",
        description = "На пополнение оборотных средств",
        purposes = setOf(FinancingPurpose.WORKING_CAPITAL),
        minAmount = 100_000,
        maxAmount = 50_000_000,
        minTermMonths = 3,
        maxTermMonths = 36,
        baseRate = BigDecimal("18.50"),
        autoApprovalLimit = 5_000_000,
        revolving = false,
    )

    private fun validTerms(bureauConsent: Boolean = false) =
        ApplicationTerms(
            productCode = product.code,
            purpose = FinancingPurpose.WORKING_CAPITAL,
            amount = 2_000_000,
            termMonths = 12,
            declaredRevenue12m = 20_000_000,
            declaredMonthlyDebtPayments = 100_000,
            bureauConsent = bureauConsent,
        )

    private fun agentApplication(
        status: ApplicationStatus = ApplicationStatus.DRAFT,
        terms: ApplicationTerms = validTerms(bureauConsent = false),
        agentId: UUID? = agentUserId,
    ) = LoanApplication(
        company = CompanySnapshot(companyId, "7707083893", "ООО Ромашка", LegalForm.LEGAL_ENTITY),
        createdBy = agentId ?: clientUserId,
        terms = terms,
        status = status,
        createdAt = now,
        agentId = agentId,
        agentName = if (agentId != null) "Иван Агентов" else null,
    )

    @Test
    fun `requestClientConfirmation moves DRAFT to PENDING_CLIENT_CONFIRMATION without bureauConsent and rejects CLIENT`() {
        val draft = agentApplication(status = ApplicationStatus.DRAFT, terms = validTerms(bureauConsent = false))

        assertFailsWith<ConflictException> {
            draft.requestClientConfirmation(product, clientActor, now)
        }
        assertFailsWith<ConflictException> {
            draft.requestClientConfirmation(product, otherAgentActor, now)
        }

        val invalidDraft = draft.copy(terms = validTerms(bureauConsent = false).copy(declaredRevenue12m = null))
        assertFailsWith<IllegalArgumentException> {
            invalidDraft.requestClientConfirmation(product, agentActor, now)
        }

        val transition = draft.requestClientConfirmation(product, agentActor, now)
        assertEquals(ApplicationStatus.PENDING_CLIENT_CONFIRMATION, transition.application.status)
        assertEquals(ApplicationEvent.REQUEST_CLIENT_CONFIRMATION.name, transition.entry.event)
        assertEquals(agentActor, transition.entry.actor)
    }

    @Test
    fun `confirmAndSubmit moves PENDING_CLIENT_CONFIRMATION to SUBMITTED only for CLIENT with bureauConsent true`() {
        val pending = agentApplication(status = ApplicationStatus.PENDING_CLIENT_CONFIRMATION, terms = validTerms(bureauConsent = false))

        assertFailsWith<ConflictException> {
            pending.confirmAndSubmit(product, bureauConsent = true, actor = agentActor, now = now)
        }
        assertFailsWith<IllegalArgumentException> {
            pending.confirmAndSubmit(product, bureauConsent = false, actor = clientActor, now = now)
        }

        val transition = pending.confirmAndSubmit(product, bureauConsent = true, actor = clientActor, now = now)
        assertEquals(ApplicationStatus.SUBMITTED, transition.application.status)
        assertTrue(transition.application.terms.bureauConsent)
        assertEquals(now, transition.application.submittedAt)
        assertEquals(ApplicationEvent.CONFIRM_AND_SUBMIT.name, transition.entry.event)
        assertEquals(clientActor, transition.entry.actor)
    }

    @Test
    fun `direct submit on agent application throws ConflictException`() {
        val draft = agentApplication(status = ApplicationStatus.DRAFT, terms = validTerms(bureauConsent = true))

        assertFailsWith<ConflictException> {
            draft.submit(product, clientActor, now)
        }
    }

    @Test
    fun `returnToAgent moves PENDING_CLIENT_CONFIRMATION back to DRAFT with comment`() {
        val pending = agentApplication(status = ApplicationStatus.PENDING_CLIENT_CONFIRMATION)

        assertFailsWith<ConflictException> {
            pending.returnToAgent(agentActor, "Нельзя", now)
        }

        val transition = pending.returnToAgent(clientActor, "Уменьшите сумму до 1.5 млн", now)
        assertEquals(ApplicationStatus.DRAFT, transition.application.status)
        assertEquals(ApplicationEvent.RETURN_TO_AGENT.name, transition.entry.event)
        assertEquals("Уменьшите сумму до 1.5 млн", transition.entry.comment)
    }

    @Test
    fun `visibility rules hide agent DRAFT from client and hide PENDING_CLIENT_CONFIRMATION from bank`() {
        val agentDraft = agentApplication(status = ApplicationStatus.DRAFT)
        val agentPending = agentApplication(status = ApplicationStatus.PENDING_CLIENT_CONFIRMATION)
        val clientDraft = agentApplication(status = ApplicationStatus.DRAFT, agentId = null)

        assertFalse(agentDraft.visibleToClient)
        assertFalse(agentDraft.visibleToBank)

        assertTrue(agentPending.visibleToClient)
        assertFalse(agentPending.visibleToBank)

        assertTrue(clientDraft.visibleToClient)
        assertFalse(clientDraft.visibleToBank)

        val clientClaims = TokenClaims(
            userId = clientUserId,
            fullName = "Клиент Клиентов",
            email = "client@example.com",
            role = Roles.CLIENT,
            companyId = companyId,
            canSign = true,
        )
        val agentClaims = TokenClaims(
            userId = agentUserId,
            fullName = "Иван Агентов",
            email = "agent@example.com",
            role = Roles.AGENT,
            companyId = null,
            canSign = false,
        )
        val otherAgentClaims = TokenClaims(
            userId = otherAgentId,
            fullName = "Другой Агент",
            email = "other-agent@example.com",
            role = Roles.AGENT,
            companyId = null,
            canSign = false,
        )
        val underwriterClaims = TokenClaims(
            userId = UUID.randomUUID(),
            fullName = "Андеррайтер Банка",
            email = "uw@example.com",
            role = Roles.UNDERWRITER,
            companyId = null,
            canSign = false,
        )

        assertEquals(Actor(ActorType.AGENT, agentUserId), agentClaims.toActor())
        assertFalse(clientClaims.canSee(agentDraft))
        assertTrue(clientClaims.canSee(agentPending))
        assertTrue(clientClaims.canSee(clientDraft))
        assertTrue(agentClaims.canSee(agentDraft))
        assertFalse(otherAgentClaims.canSee(agentDraft))
        assertFalse(underwriterClaims.canSee(agentPending))
    }

    @Test
    fun `agentCancel works only from DRAFT and PENDING_CLIENT_CONFIRMATION`() {
        val draft = agentApplication(status = ApplicationStatus.DRAFT)
        val pending = agentApplication(status = ApplicationStatus.PENDING_CLIENT_CONFIRMATION)
        val submitted = agentApplication(status = ApplicationStatus.SUBMITTED)

        assertEquals(ApplicationStatus.WITHDRAWN, draft.agentCancel(agentActor, now).application.status)
        assertEquals(ApplicationStatus.WITHDRAWN, pending.agentCancel(agentActor, now).application.status)
        assertFailsWith<ConflictException> {
            submitted.agentCancel(agentActor, now)
        }
        assertFailsWith<ConflictException> {
            draft.agentCancel(clientActor, now)
        }
        assertFailsWith<ConflictException> {
            draft.agentCancel(otherAgentActor, now)
        }
    }

    @Test
    fun `INFO_PROVIDED is allowed for both CLIENT and AGENT`() {
        val infoRequested = agentApplication(status = ApplicationStatus.INFO_REQUESTED)

        val byClient = infoRequested.move(ApplicationEvent.INFO_PROVIDED, clientActor, now)
        assertEquals(ApplicationStatus.SUBMITTED, byClient.application.status)

        val byAgent = infoRequested.move(ApplicationEvent.INFO_PROVIDED, agentActor, now)
        assertEquals(ApplicationStatus.SUBMITTED, byAgent.application.status)
        assertEquals(agentActor, byAgent.entry.actor)
    }

    @Test
    fun `new commission status defaults to NONE`() {
        val app = agentApplication()
        assertEquals(CommissionStatus.NONE, app.agentCommissionStatus)
    }
}
