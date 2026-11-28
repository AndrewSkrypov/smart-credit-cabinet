package ru.creditcabinet.agent.application

import ru.creditcabinet.access.application.actor
import ru.creditcabinet.access.domain.AccountAccessPort
import ru.creditcabinet.agent.domain.AgentMandate
import ru.creditcabinet.agent.domain.AgentMandatePort
import ru.creditcabinet.agent.domain.AgentMandateSource
import ru.creditcabinet.agent.domain.AgentMandateStatus
import ru.creditcabinet.agent.domain.CompanyAgentMandate
import ru.creditcabinet.auth.application.IdentityActor
import ru.creditcabinet.identity.application.IdentityTransactions
import ru.creditcabinet.identity.domain.IdentityFailure.Conflict
import ru.creditcabinet.identity.domain.IdentityFailure.Forbidden
import ru.creditcabinet.identity.domain.IdentityFailure.NotFound
import ru.creditcabinet.user.domain.UserRole
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

class CompanyAgentMandates(
    private val accounts: AccountAccessPort,
    private val mandates: AgentMandatePort,
    private val transactions: IdentityTransactions,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun list(claims: IdentityActor): List<CompanyAgentMandate> = transactions.read {
        val companyId = company(claims, lock = false)
        val now = clock.instant()
        mandates.listForCompany(companyId).map { record ->
            record.copy(mandate = record.mandate.copy(status = record.mandate.statusAt(now)))
        }
    }

    fun issue(claims: IdentityActor, agentUserId: UUID, expiresAt: Instant): CompanyAgentMandate = transactions.write {
        val companyId = company(claims, lock = true)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val expiry = expiresAt.truncatedTo(ChronoUnit.MICROS)
        require(expiry.isAfter(now)) { "Срок действия мандата должен быть в будущем" }
        val agentName = mandates.activeAgentName(agentUserId) ?: throw NotFound("Агент не найден")
        val current = mandates.currentGrant(companyId, agentUserId)
        if (current != null) {
            if (current.statusAt(now) == AgentMandateStatus.ACTIVE) {
                if (current.expiresAt == expiry) return@write CompanyAgentMandate(current, agentName)
                throw Conflict("Для этого агента уже действует мандат")
            }
            mandates.markExpired(companyId, current.id)
        }
        val mandate = AgentMandate(
            agentUserId = agentUserId,
            companyId = companyId,
            assignedAt = now,
            expiresAt = expiry,
            issuedSource = AgentMandateSource.COMPANY,
            issuedBy = claims.userId,
        )
        mandates.insert(mandate)
        CompanyAgentMandate(mandate, agentName)
    }

    fun revoke(claims: IdentityActor, id: UUID): Unit = transactions.write {
        val companyId = company(claims, lock = true)
        val mandate = mandates.findForCompany(companyId, id) ?: throw NotFound("Мандат не найден")
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        if (mandate.statusAt(now) == AgentMandateStatus.ACTIVE) mandates.revoke(mandate.revoke(now, claims.userId))
        Unit
    }

    private fun company(claims: IdentityActor, lock: Boolean): UUID {
        if (!claims.isClient) throw Forbidden("Раздел доступен представителям компании")
        val companyId = claims.companyId ?: throw Forbidden("Компания не подключена")
        if (lock) accounts.lockCompany(companyId)
        val actor = accounts.actor(claims, lock).user
        if (actor.companyId != companyId || actor.role != UserRole.CLIENT || !actor.canSign) {
            throw Forbidden("Нужно право подписи от компании")
        }
        return companyId
    }
}
