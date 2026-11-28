package ru.creditcabinet.access.application

import ru.creditcabinet.access.domain.AccountAccessPort
import ru.creditcabinet.access.domain.StoredAccount
import ru.creditcabinet.auth.application.IdentityActor
import ru.creditcabinet.identity.domain.IdentityFailure.Unauthorized
import ru.creditcabinet.user.domain.UserStatus

internal fun AccountAccessPort.actor(claims: IdentityActor, lock: Boolean = true): StoredAccount {
    val account = account(claims.userId, lock) ?: throw Unauthorized()
    if (account.user.status != UserStatus.ACTIVE || account.user.credentialsVersion != claims.credentialsVersion) throw Unauthorized()
    return account
}
