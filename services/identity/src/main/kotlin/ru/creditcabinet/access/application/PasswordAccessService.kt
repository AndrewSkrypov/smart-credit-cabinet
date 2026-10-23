package ru.creditcabinet.access.application

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.access.domain.AccessGrant
import ru.creditcabinet.access.domain.AccountAccessPort
import ru.creditcabinet.access.domain.AccountLinkIssuer
import ru.creditcabinet.access.domain.LinkPurpose
import ru.creditcabinet.access.domain.RecoveryLimits
import ru.creditcabinet.access.domain.SecurityMailReference
import ru.creditcabinet.access.domain.StoredReset
import ru.creditcabinet.access.dto.AccessMessage
import ru.creditcabinet.access.dto.ChangePasswordRequest
import ru.creditcabinet.access.dto.ResetPasswordRequest
import ru.creditcabinet.auth.application.PasswordPolicy
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.common.mail.MailCapabilities
import ru.creditcabinet.common.outbox.DurableOutbox
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.user.domain.UserStatus
import java.time.Clock
import java.util.UUID

@Service
class PasswordAccessService(
    private val store: AccountAccessPort,
    private val encoder: PasswordEncoder,
    private val links: AccountLinkIssuer,
    private val mail: MailCapabilities,
    private val outbox: DurableOutbox,
    private val mapper: ObjectMapper,
    private val throttle: RecoveryLimits,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun requestRecovery(rawEmail: String, address: String): AccessMessage {
        if (!mail.enabled()) throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Восстановление через почту на этом стенде выключено")
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
            outbox.enqueue("email-security", "password-reset:$id", mapper.writeValueAsString(SecurityMailReference(LinkPurpose.PASSWORD_RESET, id)))
            store.audit(null, "PASSWORD_RESET_REQUESTED", user.id, user.companyId, now)
        } else {
            store.audit(null, "PASSWORD_RESET_REQUESTED", null, null, now)
        }
        return AccessMessage("Если адрес зарегистрирован, на него придёт ссылка для восстановления пароля. Ссылка действует 30 минут.")
    }

    @Transactional
    fun resetPassword(request: ResetPasswordRequest): AccessMessage {
        val id = AccessGrant.tokenId(request.token)
        val initial = store.reset(id) ?: AccessGrant.expiredLink()
        initial.grant.requireValid(request.token, clock.instant())
        val account = store.account(initial.userId, true) ?: AccessGrant.expiredLink()
        val reset = store.reset(id, true) ?: AccessGrant.expiredLink()
        val now = clock.instant()
        reset.grant.requireValid(request.token, now)
        if (account.user.status != UserStatus.ACTIVE || account.user.credentialsVersion != reset.credentialsVersion) AccessGrant.expiredLink()
        PasswordPolicy.check(request.password)
        store.useReset(id, now)
        store.changePassword(account.user.id, encoder.encode(request.password))
        store.revokeResets(account.user.id, now)
        store.audit(account.user.id, "PASSWORD_RESET", account.user.id, account.user.companyId, now)
        return AccessMessage("Пароль изменён. Войдите с новым паролем.")
    }

    @Transactional
    fun changePassword(claims: TokenClaims, request: ChangePasswordRequest): AccessMessage {
        if (request.currentPassword.length > 72 || request.currentPassword.toByteArray(Charsets.UTF_8).size > 72) {
            throw BadRequestException("Текущий пароль слишком длинный")
        }
        val account = store.actor(claims)
        if (!encoder.matches(request.currentPassword, account.passwordHash)) throw BadRequestException("Текущий пароль указан неверно")
        PasswordPolicy.check(request.password)
        store.changePassword(account.user.id, encoder.encode(request.password))
        store.revokeResets(account.user.id, clock.instant())
        store.audit(account.user.id, "PASSWORD_CHANGED", account.user.id, account.user.companyId, clock.instant())
        return AccessMessage("Пароль изменён. Войдите заново на всех устройствах.")
    }

    @Transactional
    fun logoutAll(claims: TokenClaims): AccessMessage {
        val account = store.actor(claims)
        store.revokeSessions(account.user.id)
        store.revokeResets(account.user.id, clock.instant())
        store.audit(account.user.id, "SESSIONS_REVOKED", account.user.id, account.user.companyId, clock.instant())
        return AccessMessage("Вы вышли на всех устройствах. Войдите заново.")
    }
}
