package ru.creditcabinet.access.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.access.domain.AccessGrant
import ru.creditcabinet.access.domain.AccountAccessPort
import ru.creditcabinet.access.domain.AccountLinkIssuer
import ru.creditcabinet.access.domain.LinkPurpose
import ru.creditcabinet.access.domain.SecurityMailReference
import ru.creditcabinet.access.domain.StoredInvitation
import ru.creditcabinet.access.dto.AcceptInvitationRequest
import ru.creditcabinet.access.dto.AccessMessage
import ru.creditcabinet.access.dto.EmployeeListResponse
import ru.creditcabinet.access.dto.InvitationPreview
import ru.creditcabinet.access.dto.InvitationResponse
import ru.creditcabinet.access.dto.InviteEmployeeRequest
import ru.creditcabinet.auth.application.ConsentLog
import ru.creditcabinet.auth.application.JwtService
import ru.creditcabinet.auth.application.PasswordPolicy
import ru.creditcabinet.auth.dto.UserSessionResponse
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.ForbiddenException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.mail.MailCapabilities
import ru.creditcabinet.common.outbox.DurableOutbox
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.user.domain.User
import ru.creditcabinet.user.domain.UserRole
import ru.creditcabinet.user.domain.UserStatus
import ru.creditcabinet.user.dto.UserResponse
import java.time.Clock
import java.util.UUID

@Service
class CompanyAccessService(
    private val store: AccountAccessPort,
    private val links: AccountLinkIssuer,
    private val mail: MailCapabilities,
    private val outbox: DurableOutbox,
    private val mapper: ObjectMapper,
    private val encoder: PasswordEncoder,
    private val jwt: JwtService,
    private val consents: ConsentLog,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional(readOnly = true)
    fun list(claims: TokenClaims): EmployeeListResponse {
        val company = requireCompany(claims)
        val actor = store.account(claims.userId)?.user ?: throw ForbiddenException("Пользователь недоступен")
        return EmployeeListResponse(
            store.members(company).map(UserResponse::of),
            store.invitations(company).map { response(it) },
            actor.canSign,
            mail.enabled(),
        )
    }

    @Transactional
    fun invite(claims: TokenClaims, key: UUID, request: InviteEmployeeRequest): InvitationResponse {
        val actor = manage(claims)
        val company = actor.companyId!!
        val email = request.email.trim().lowercase()
        val payload = "$email:${request.canSign}"
        store.replay(actor.id, key, "INVITE", payload)?.let { return response(ownInvitation(company, it)) }
        checkEmail(company, email)
        val now = clock.instant()
        store.expireInvitations(company, email, now)
        if (store.invitations(company).any { it.email == email && it.grant.available(now) }) throw ConflictException("Для этого адреса уже есть приглашение")
        val sendMail = mail.enabled()
        val invitation = create(actor, email, request.canSign, sendMail)
        store.remember(actor.id, key, "INVITE", payload, invitation.grant.id)
        return response(invitation, reveal = !sendMail)
    }

    @Transactional
    fun resend(claims: TokenClaims, id: UUID, key: UUID): InvitationResponse {
        val actor = manage(claims)
        val company = actor.companyId!!
        val original = ownInvitation(company, id)
        store.replay(actor.id, key, "RESEND", id.toString())?.let { return response(ownInvitation(company, it)) }
        if (original.grant.usedAt != null || original.grant.revokedAt != null) throw ConflictException("Это приглашение уже использовано или отменено")
        checkEmail(company, original.email)
        cancel(original.grant.id)
        val sendMail = mail.enabled()
        val next = create(actor, original.email, original.canSign, sendMail)
        store.audit(actor.id, "INVITATION_RESENT", original.grant.id, company, clock.instant())
        store.remember(actor.id, key, "RESEND", id.toString(), next.grant.id)
        return response(next, reveal = !sendMail)
    }

    @Transactional
    fun cancelInvitation(claims: TokenClaims, id: UUID, key: UUID): AccessMessage {
        val actor = manage(claims)
        val invitation = ownInvitation(actor.companyId!!, id)
        if (store.replay(actor.id, key, "CANCEL_INVITE", id.toString()) == null) {
            if (invitation.grant.usedAt != null) throw ConflictException("Приглашение уже принято. Отзовите доступ сотрудника.")
            if (invitation.grant.revokedAt == null) {
                cancel(id)
                store.audit(actor.id, "INVITATION_REVOKED", id, actor.companyId, clock.instant())
            }
            store.remember(actor.id, key, "CANCEL_INVITE", id.toString(), id)
        }
        return AccessMessage("Приглашение отменено")
    }

    @Transactional
    fun revoke(claims: TokenClaims, id: UUID, key: UUID): AccessMessage {
        val actor = manage(claims)
        val target = store.account(id, true)?.user?.takeIf { it.companyId == actor.companyId } ?: throw NotFoundException("Сотрудник не найден")
        if (store.replay(actor.id, key, "REVOKE", id.toString()) == null) {
            if (target.status == UserStatus.ACTIVE) {
                if (target.canSign && store.members(actor.companyId!!).count { it.status == UserStatus.ACTIVE && it.canSign } <= 1) {
                    throw ConflictException("Нельзя отозвать доступ последнего сотрудника с правом подписи")
                }
                store.revokeEmployee(id)
                store.revokeResets(id, clock.instant())
                store.cancelInvitationsByActor(id, clock.instant())
                store.audit(actor.id, "EMPLOYEE_REVOKED", id, actor.companyId, clock.instant())
            }
            store.remember(actor.id, key, "REVOKE", id.toString(), id)
        }
        return AccessMessage("Доступ сотрудника отозван")
    }

    fun preview(token: String): InvitationPreview {
        val invitation = store.invitation(AccessGrant.tokenId(token)) ?: AccessGrant.expiredLink()
        invitation.grant.requireValid(token, clock.instant())
        val company = store.companyName(invitation.companyId) ?: AccessGrant.expiredLink()
        return InvitationPreview(company, invitation.email, invitation.canSign, invitation.grant.expiresAt)
    }

    @Transactional
    fun accept(request: AcceptInvitationRequest): UserSessionResponse {
        val id = AccessGrant.tokenId(request.token)
        val initial = store.invitation(id) ?: AccessGrant.expiredLink()
        initial.grant.requireValid(request.token, clock.instant())
        store.lockCompany(initial.companyId)
        val invitation = store.invitation(id, true) ?: AccessGrant.expiredLink()
        invitation.grant.requireValid(request.token, clock.instant())
        PasswordPolicy.check(request.password)
        val existing = store.accountByEmail(invitation.email)?.let { store.account(it.user.id, true) }?.user
        if (existing != null && (existing.companyId != invitation.companyId || existing.status != UserStatus.BLOCKED || existing.role != UserRole.CLIENT)) {
            throw ConflictException("Этот адрес уже используется. Попросите приглашающего указать другой.")
        }
        val user = User(
            id = existing?.id ?: UUID.randomUUID(),
            fullName = request.fullName.trim(),
            email = invitation.email,
            role = UserRole.CLIENT,
            companyId = invitation.companyId,
            canSign = invitation.canSign,
            credentialsVersion = existing?.credentialsVersion?.plus(1) ?: 0,
        )
        store.saveInvitee(user, encoder.encode(request.password))
        store.useInvitation(id, user.id, clock.instant())
        consents.record(user.id, ConsentLog.PERSONAL_DATA, ConsentLog.PERSONAL_DATA_VERSION)
        store.audit(user.id, "INVITATION_ACCEPTED", id, user.companyId, clock.instant())
        return UserSessionResponse(jwt.issue(user), UserResponse.of(user))
    }

    private fun manage(claims: TokenClaims): User {
        val company = requireCompany(claims)
        store.lockCompany(company)
        val actor = store.actor(claims).user
        if (!actor.canSign || actor.companyId != company || actor.role != UserRole.CLIENT) throw ForbiddenException("Нужно право подписи от компании")
        return actor
    }

    private fun requireCompany(claims: TokenClaims): UUID {
        if (claims.role != "CLIENT") throw ForbiddenException("Раздел доступен представителям компании")
        return claims.companyId ?: throw ForbiddenException("Компания не подключена")
    }

    private fun ownInvitation(company: UUID, id: UUID): StoredInvitation =
        store.invitation(id)?.takeIf { it.companyId == company } ?: throw NotFoundException("Приглашение не найдено")

    private fun checkEmail(company: UUID, email: String) {
        val existing = store.accountByEmail(email)?.user ?: return
        if (existing.companyId != company || existing.role != UserRole.CLIENT || existing.status != UserStatus.BLOCKED) {
            throw ConflictException("Этот адрес уже используется. Укажите другой адрес.")
        }
    }

    private fun create(actor: User, email: String, canSign: Boolean, sendMail: Boolean): StoredInvitation {
        val id = UUID.randomUUID()
        val now = clock.instant()
        store.insertInvitation(
            StoredInvitation(
                AccessGrant(id, links.hash(LinkPurpose.COMPANY_INVITATION, id), now.plusSeconds(72 * 3600), null, null),
                actor.companyId!!,
                email,
                canSign,
                actor.id,
                links.keyVersion,
            ),
            now,
        )
        if (sendMail) {
            outbox.enqueue("email-security", "company-invitation:$id", mapper.writeValueAsString(SecurityMailReference(LinkPurpose.COMPANY_INVITATION, id)))
        }
        store.audit(actor.id, "INVITATION_CREATED", id, actor.companyId, now)
        return store.invitation(id)!!
    }

    private fun cancel(id: UUID) {
        store.cancelInvitation(id, clock.instant())
    }

    private fun response(invitation: StoredInvitation, reveal: Boolean = false): InvitationResponse {
        val grant = invitation.grant
        val status = when {
            grant.usedAt != null -> "ACCEPTED"
            grant.revokedAt != null -> "REVOKED"
            !clock.instant().isBefore(grant.expiresAt) -> "EXPIRED"
            else -> "PENDING"
        }
        return InvitationResponse(
            grant.id,
            invitation.email,
            invitation.canSign,
            grant.expiresAt,
            status,
            if (reveal) links.link(LinkPurpose.COMPANY_INVITATION, grant.id) else null,
        )
    }
}
