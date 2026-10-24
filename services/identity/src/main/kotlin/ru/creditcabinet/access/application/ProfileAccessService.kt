package ru.creditcabinet.access.application

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.access.domain.AccountAccessPort
import ru.creditcabinet.access.dto.UpdateProfileRequest
import ru.creditcabinet.auth.application.JwtService
import ru.creditcabinet.auth.dto.UserSessionResponse
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.user.dto.UserResponse
import java.time.Clock

/**
 * The person's own name and email. The token carries both, so a new session is returned; older
 * tokens stay valid until they expire. A new email is the new login: it needs the current
 * password, and recovery links sent to the old address stop working.
 */
@Service
class ProfileAccessService(
    private val store: AccountAccessPort,
    private val encoder: PasswordEncoder,
    private val tokens: JwtService,
    private val clock: Clock = Clock.systemUTC(),
) {
    @Transactional
    fun update(claims: TokenClaims, request: UpdateProfileRequest): UserSessionResponse {
        val account = store.actor(claims)
        val fullName = request.fullName.trim().replace(Regex("\\s+"), " ")
        val email = request.email.trim().lowercase()
        val emailChanged = email != account.user.email
        if (emailChanged) {
            val password = request.currentPassword.orEmpty()
            if (password.isEmpty()) throw BadRequestException("Чтобы сменить email, укажите текущий пароль")
            if (!encoder.matches(password, account.passwordHash)) throw BadRequestException("Текущий пароль указан неверно")
        }
        val user = account.user.copy(fullName = fullName, email = email)
        if (user == account.user) return UserSessionResponse(tokens.issue(user), UserResponse.of(user))
        store.updateProfile(user.id, fullName, email)
        val now = clock.instant()
        if (emailChanged) {
            store.revokeResets(user.id, now)
            store.audit(user.id, "EMAIL_CHANGED", user.id, user.companyId, now)
        }
        if (fullName != account.user.fullName) store.audit(user.id, "NAME_CHANGED", user.id, user.companyId, now)
        return UserSessionResponse(tokens.issue(user), UserResponse.of(user))
    }
}
