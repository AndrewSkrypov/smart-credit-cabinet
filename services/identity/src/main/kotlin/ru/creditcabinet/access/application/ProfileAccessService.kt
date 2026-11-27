package ru.creditcabinet.access.application

import ru.creditcabinet.access.domain.AccountAccessPort
import ru.creditcabinet.auth.application.IdentityActor
import ru.creditcabinet.auth.application.IdentitySession
import ru.creditcabinet.auth.application.PasswordHashes
import ru.creditcabinet.auth.application.SessionTokens
import ru.creditcabinet.identity.application.IdentityTransactions
import ru.creditcabinet.identity.domain.IdentityFailure.InvalidInput
import java.time.Clock

/**
 * The person's own name and email. The token carries both, so a new session is returned; older
 * tokens stay valid until they expire. A new email is the new login: it needs the current
 * password, and recovery links sent to the old address stop working.
 */
class ProfileAccessService(
    private val store: AccountAccessPort,
    private val passwords: PasswordHashes,
    private val tokens: SessionTokens,
    private val transactions: IdentityTransactions,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun update(claims: IdentityActor, rawName: String, rawEmail: String, currentPassword: String?): IdentitySession = transactions.write {
        val account = store.actor(claims)
        val fullName = rawName.trim().replace(Regex("\\s+"), " ")
        val email = rawEmail.trim().lowercase()
        val emailChanged = email != account.user.email
        if (emailChanged) {
            val password = currentPassword.orEmpty()
            if (password.isEmpty()) throw InvalidInput("Чтобы сменить email, укажите текущий пароль")
            if (!passwords.matches(password, account.passwordHash)) throw InvalidInput("Текущий пароль указан неверно")
        }
        val user = account.user.copy(fullName = fullName, email = email)
        if (user == account.user) return@write IdentitySession(tokens.issue(user), user)
        store.updateProfile(user.id, fullName, email)
        val now = clock.instant()
        if (emailChanged) {
            store.revokeResets(user.id, now)
            store.audit(user.id, "EMAIL_CHANGED", user.id, user.companyId, now)
        }
        if (fullName != account.user.fullName) store.audit(user.id, "NAME_CHANGED", user.id, user.companyId, now)
        IdentitySession(tokens.issue(user), user)
    }
}
