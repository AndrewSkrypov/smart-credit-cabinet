package ru.creditcabinet.agent.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.access.application.actor
import ru.creditcabinet.access.domain.AccountAccessPort
import ru.creditcabinet.agent.domain.AgentMandate
import ru.creditcabinet.agent.domain.AgentMandatePort
import ru.creditcabinet.agent.domain.AgentMandateSource
import ru.creditcabinet.agent.domain.AgentMandateStatus
import ru.creditcabinet.agent.domain.CompanyAgentMandate
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.ForbiddenException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.user.domain.UserRole
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
class CompanyAgentMandates(
    private val accounts: AccountAccessPort,
    private val mandates: AgentMandatePort,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun list(claims: TokenClaims): List<CompanyAgentMandate> {
        val companyId = company(claims, lock = false)
        val now = clock.instant()
        return mandates.listForCompany(companyId).map { record ->
            record.copy(mandate = record.mandate.copy(status = record.mandate.statusAt(now)))
        }
    }

    @Transactional
    fun issue(claims: TokenClaims, agentUserId: UUID, expiresAt: Instant): CompanyAgentMandate {
        val companyId = company(claims, lock = true)
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        val expiry = expiresAt.truncatedTo(ChronoUnit.MICROS)
        require(expiry.isAfter(now)) { "Срок действия мандата должен быть в будущем" }
        val agentName = mandates.activeAgentName(agentUserId) ?: throw NotFoundException("Агент не найден")
        val current = mandates.currentGrant(companyId, agentUserId)
        if (current != null) {
            if (current.statusAt(now) == AgentMandateStatus.ACTIVE) {
                if (current.expiresAt == expiry) return CompanyAgentMandate(current, agentName)
                throw ConflictException("Для этого агента уже действует мандат")
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
        return CompanyAgentMandate(mandate, agentName)
    }

    @Transactional
    fun revoke(claims: TokenClaims, id: UUID) {
        val companyId = company(claims, lock = true)
        val mandate = mandates.findForCompany(companyId, id) ?: throw NotFoundException("Мандат не найден")
        val now = clock.instant().truncatedTo(ChronoUnit.MICROS)
        if (mandate.statusAt(now) == AgentMandateStatus.ACTIVE) mandates.revoke(mandate.revoke(now, claims.userId))
    }

    private fun company(claims: TokenClaims, lock: Boolean): UUID {
        if (!claims.isClient) throw ForbiddenException("Раздел доступен представителям компании")
        val companyId = claims.companyId ?: throw ForbiddenException("Компания не подключена")
        if (lock) accounts.lockCompany(companyId)
        val actor = accounts.actor(claims, lock).user
        if (actor.companyId != companyId || actor.role != UserRole.CLIENT || !actor.canSign) {
            throw ForbiddenException("Нужно право подписи от компании")
        }
        return companyId
    }
}
