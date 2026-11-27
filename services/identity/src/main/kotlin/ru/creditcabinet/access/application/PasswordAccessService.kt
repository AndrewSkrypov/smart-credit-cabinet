package ru.creditcabinet.access.application

import ru.creditcabinet.access.domain.AccessGrant
import ru.creditcabinet.access.domain.AccountAccessPort
import ru.creditcabinet.access.domain.AccountLinkIssuer
import ru.creditcabinet.access.domain.LinkPurpose
import ru.creditcabinet.access.domain.RecoveryLimits
import ru.creditcabinet.access.domain.StoredReset
import ru.creditcabinet.auth.application.IdentityActor
import ru.creditcabinet.auth.application.PasswordHashes
import ru.creditcabinet.auth.domain.PasswordPolicy
import ru.creditcabinet.identity.application.IdentityTransactions
import ru.creditcabinet.identity.domain.IdentityFailure.InvalidInput
import ru.creditcabinet.identity.domain.IdentityFailure.Unavailable
import ru.creditcabinet.user.domain.UserStatus
import java.time.Clock
import java.util.UUID

class PasswordAccessService(
    private val store: AccountAccessPort,
    private val passwords: PasswordHashes,
    private val links: AccountLinkIssuer,
    private val mail: SecurityMail,
    private val throttle: RecoveryLimits,
    private val transactions: IdentityTransactions,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun requestRecovery(rawEmail: String, address: String): String = transactions.write {
        if (!mail.enabled()) throw Unavailable("Восстановление через почту на этом стенде выключено")
        val email = rawEmail.trim().lowercase()
        val now = clock.instant()
        throttle.acquire(email, address, now)
        val found = store.accountByEmail(email)
        val account = found?.let { store.account(it.user.id, true) }?.takeIf { it.user.status == UserStatus.ACTIVE }
        if (account != null) {
            val user = account.user
            val id = UUID.randomUUID()
            store.revokeResets(user.id, now)
            store.insertReset(
                StoredReset(
                    AccessGrant(id, links.hash(LinkPurpose.PASSWORD_RESET, id), now.plusSeconds(1800), null, null),
                    user.id,
                    user.credentialsVersion,
                    links.keyVersion,
                ),
                now,
            )
            mail.enqueue(LinkPurpose.PASSWORD_RESET, id)
            store.audit(null, "PASSWORD_RESET_REQUESTED", user.id, user.companyId, now)
        } else {
            store.audit(null, "PASSWORD_RESET_REQUESTED", null, null, now)
        }
        "Если адрес зарегистрирован, на него придёт ссылка для восстановления пароля. Ссылка действует 30 минут."
    }

    fun resetPassword(token: String, password: String): String = transactions.write {
        val id = AccessGrant.tokenId(token)
        val initial = store.reset(id) ?: AccessGrant.expiredLink()
        initial.grant.requireValid(token, clock.instant())
        val account = store.account(initial.userId, true) ?: AccessGrant.expiredLink()
        val reset = store.reset(id, true) ?: AccessGrant.expiredLink()
        val now = clock.instant()
        reset.grant.requireValid(token, now)
        if (account.user.status != UserStatus.ACTIVE || account.user.credentialsVersion != reset.credentialsVersion) AccessGrant.expiredLink()
        PasswordPolicy.check(password)
        store.useReset(id, now)
        store.changePassword(account.user.id, passwords.encode(password))
        store.revokeResets(account.user.id, now)
        store.audit(account.user.id, "PASSWORD_RESET", account.user.id, account.user.companyId, now)
        "Пароль изменён. Войдите с новым паролем."
    }

    fun changePassword(claims: IdentityActor, currentPassword: String, password: String): String = transactions.write {
        if (currentPassword.length > 72 || currentPassword.toByteArray(Charsets.UTF_8).size > 72) {
            throw InvalidInput("Текущий пароль слишком длинный")
        }
        val account = store.actor(claims)
        if (!passwords.matches(currentPassword, account.passwordHash)) throw InvalidInput("Текущий пароль указан неверно")
        PasswordPolicy.check(password)
        store.changePassword(account.user.id, passwords.encode(password))
        store.revokeResets(account.user.id, clock.instant())
        store.audit(account.user.id, "PASSWORD_CHANGED", account.user.id, account.user.companyId, clock.instant())
        "Пароль изменён. Войдите заново на всех устройствах."
    }

    fun logoutAll(claims: IdentityActor): String = transactions.write {
        val account = store.actor(claims)
        store.revokeSessions(account.user.id)
        store.revokeResets(account.user.id, clock.instant())
        store.audit(account.user.id, "SESSIONS_REVOKED", account.user.id, account.user.companyId, clock.instant())
        "Вы вышли на всех устройствах. Войдите заново."
    }
}
