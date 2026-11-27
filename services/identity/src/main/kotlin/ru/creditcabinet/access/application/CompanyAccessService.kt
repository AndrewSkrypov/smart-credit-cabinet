package ru.creditcabinet.access.application

import ru.creditcabinet.access.domain.AccessGrant
import ru.creditcabinet.access.domain.AccountAccessPort
import ru.creditcabinet.access.domain.AccountLinkIssuer
import ru.creditcabinet.access.domain.LinkPurpose
import ru.creditcabinet.access.domain.StoredInvitation
import ru.creditcabinet.auth.application.IdentityActor
import ru.creditcabinet.auth.application.IdentitySession
import ru.creditcabinet.auth.application.PasswordHashes
import ru.creditcabinet.auth.application.PersonalDataConsents
import ru.creditcabinet.auth.application.SessionTokens
import ru.creditcabinet.auth.domain.PasswordPolicy
import ru.creditcabinet.identity.application.IdentityTransactions
import ru.creditcabinet.identity.domain.IdentityFailure.Conflict
import ru.creditcabinet.identity.domain.IdentityFailure.Forbidden
import ru.creditcabinet.identity.domain.IdentityFailure.InvalidInput
import ru.creditcabinet.identity.domain.IdentityFailure.NotFound
import ru.creditcabinet.user.domain.User
import ru.creditcabinet.user.domain.UserRole
import ru.creditcabinet.user.domain.UserStatus
import java.time.Clock
import java.util.UUID

class CompanyAccessService(
    private val store: AccountAccessPort,
    private val links: AccountLinkIssuer,
    private val mail: SecurityMail,
    private val passwords: PasswordHashes,
    private val tokens: SessionTokens,
    private val consents: PersonalDataConsents,
    private val transactions: IdentityTransactions,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun list(claims: IdentityActor): EmployeeAccess = transactions.read {
        val company = requireCompany(claims)
        val actor = store.account(claims.userId)?.user ?: throw Forbidden("Пользователь недоступен")
        EmployeeAccess(
            store.members(company),
            store.invitations(company).map { response(it) },
            actor.canSign,
            mail.enabled(),
        )
    }

    fun invite(claims: IdentityActor, key: UUID, rawEmail: String, canSign: Boolean): InvitationDetails = transactions.write {
        val actor = manage(claims)
        val company = actor.companyId!!
        val email = rawEmail.trim().lowercase()
        val payload = "$email:$canSign"
        store.replay(actor.id, key, "INVITE", payload)?.let { return@write response(ownInvitation(company, it)) }
        checkEmail(company, email)
        val now = clock.instant()
        store.expireInvitations(company, email, now)
        if (store.invitations(company).any { it.email == email && it.grant.available(now) }) throw Conflict("Для этого адреса уже есть приглашение")
        val sendMail = mail.enabled()
        val invitation = create(actor, email, canSign, sendMail)
        store.remember(actor.id, key, "INVITE", payload, invitation.grant.id)
        response(invitation, reveal = !sendMail)
    }

    fun resend(claims: IdentityActor, id: UUID, key: UUID): InvitationDetails = transactions.write {
        val actor = manage(claims)
        val company = actor.companyId!!
        val original = ownInvitation(company, id)
        store.replay(actor.id, key, "RESEND", id.toString())?.let { return@write response(ownInvitation(company, it)) }
        if (original.grant.usedAt != null || original.grant.revokedAt != null) throw Conflict("Это приглашение уже использовано или отменено")
        checkEmail(company, original.email)
        cancel(original.grant.id)
        val sendMail = mail.enabled()
        val next = create(actor, original.email, original.canSign, sendMail)
        store.audit(actor.id, "INVITATION_RESENT", original.grant.id, company, clock.instant())
        store.remember(actor.id, key, "RESEND", id.toString(), next.grant.id)
        response(next, reveal = !sendMail)
    }

    fun cancelInvitation(claims: IdentityActor, id: UUID, key: UUID): String = transactions.write {
        val actor = manage(claims)
        val invitation = ownInvitation(actor.companyId!!, id)
        if (store.replay(actor.id, key, "CANCEL_INVITE", id.toString()) == null) {
            if (invitation.grant.usedAt != null) throw Conflict("Приглашение уже принято. Отзовите доступ сотрудника.")
            if (invitation.grant.revokedAt == null) {
                cancel(id)
                store.audit(actor.id, "INVITATION_REVOKED", id, actor.companyId, clock.instant())
            }
            store.remember(actor.id, key, "CANCEL_INVITE", id.toString(), id)
        }
        "Приглашение отменено"
    }

    fun revoke(claims: IdentityActor, id: UUID, key: UUID): String = transactions.write {
        val actor = manage(claims)
        if (id == actor.id) throw Forbidden("Нельзя отозвать доступ у себя")
        val target = store.account(id, true)?.user?.takeIf { it.companyId == actor.companyId } ?: throw NotFound("Сотрудник не найден")
        if (store.replay(actor.id, key, "REVOKE", id.toString()) == null) {
            if (target.status == UserStatus.ACTIVE) {
                if (target.canSign && store.members(actor.companyId!!).count { it.status == UserStatus.ACTIVE && it.canSign } <= 1) {
                    throw Conflict("Нельзя отозвать доступ последнего сотрудника с правом подписи")
                }
                store.revokeEmployee(id)
                store.revokeResets(id, clock.instant())
                store.cancelInvitationsByActor(id, clock.instant())
                store.audit(actor.id, "EMPLOYEE_REVOKED", id, actor.companyId, clock.instant())
            }
            store.remember(actor.id, key, "REVOKE", id.toString(), id)
        }
        "Доступ сотрудника отозван"
    }

    fun preview(token: String): InvitationInspection {
        val invitation = store.invitation(AccessGrant.tokenId(token)) ?: AccessGrant.expiredLink()
        invitation.grant.requireValid(token, clock.instant())
        val company = store.companyName(invitation.companyId) ?: AccessGrant.expiredLink()
        return InvitationInspection(company, invitation.email, invitation.canSign, invitation.grant.expiresAt)
    }

    fun accept(token: String, fullName: String, password: String, personalDataConsent: Boolean): IdentitySession = transactions.write {
        if (!personalDataConsent) throw InvalidInput("Без согласия на обработку персональных данных принять приглашение нельзя")
        val id = AccessGrant.tokenId(token)
        val initial = store.invitation(id) ?: AccessGrant.expiredLink()
        initial.grant.requireValid(token, clock.instant())
        store.lockCompany(initial.companyId)
        val invitation = store.invitation(id, true) ?: AccessGrant.expiredLink()
        invitation.grant.requireValid(token, clock.instant())
        PasswordPolicy.check(password)
        val existing = store.accountByEmail(invitation.email)?.let { store.account(it.user.id, true) }?.user
        if (existing != null && (existing.companyId != invitation.companyId || existing.status != UserStatus.BLOCKED || existing.role != UserRole.CLIENT)) {
            throw Conflict("Этот адрес уже используется. Попросите приглашающего указать другой.")
        }
        val user = User(
            id = existing?.id ?: UUID.randomUUID(),
            fullName = fullName.trim(),
            email = invitation.email,
            role = UserRole.CLIENT,
            companyId = invitation.companyId,
            canSign = invitation.canSign,
            credentialsVersion = existing?.credentialsVersion?.plus(1) ?: 0,
        )
        store.saveInvitee(user, passwords.encode(password))
        store.useInvitation(id, user.id, clock.instant())
        consents.record(user.id)
        store.audit(user.id, "INVITATION_ACCEPTED", id, user.companyId, clock.instant())
        IdentitySession(tokens.issue(user), user)
    }

    private fun manage(claims: IdentityActor): User {
        val company = requireCompany(claims)
        store.lockCompany(company)
        val actor = store.actor(claims).user
        if (!actor.canSign || actor.companyId != company || actor.role != UserRole.CLIENT) throw Forbidden("Нужно право подписи от компании")
        return actor
    }

    private fun requireCompany(claims: IdentityActor): UUID {
        if (!claims.isClient) throw Forbidden("Раздел доступен представителям компании")
        return claims.companyId ?: throw Forbidden("Компания не подключена")
    }

    private fun ownInvitation(company: UUID, id: UUID): StoredInvitation =
        store.invitation(id)?.takeIf { it.companyId == company } ?: throw NotFound("Приглашение не найдено")

    private fun checkEmail(company: UUID, email: String) {
        val existing = store.accountByEmail(email)?.user ?: return
        if (existing.companyId != company || existing.role != UserRole.CLIENT || existing.status != UserStatus.BLOCKED) {
            throw Conflict("Этот адрес уже используется. Укажите другой адрес.")
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
            mail.enqueue(LinkPurpose.COMPANY_INVITATION, id)
        }
        store.audit(actor.id, "INVITATION_CREATED", id, actor.companyId, now)
        return store.invitation(id)!!
    }

    private fun cancel(id: UUID) {
        store.cancelInvitation(id, clock.instant())
    }

    private fun response(invitation: StoredInvitation, reveal: Boolean = false): InvitationDetails {
        val grant = invitation.grant
        val status = when {
            grant.usedAt != null -> "ACCEPTED"
            grant.revokedAt != null -> "REVOKED"
            !clock.instant().isBefore(grant.expiresAt) -> "EXPIRED"
            else -> "PENDING"
        }
        return InvitationDetails(
            grant.id,
            invitation.email,
            invitation.canSign,
            grant.expiresAt,
            status,
            if (reveal) links.link(LinkPurpose.COMPANY_INVITATION, grant.id) else null,
        )
    }
}
