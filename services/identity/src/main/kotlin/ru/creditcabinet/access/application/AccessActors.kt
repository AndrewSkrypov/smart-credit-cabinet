package ru.creditcabinet.access.application

import ru.creditcabinet.access.domain.AccountAccessPort
import ru.creditcabinet.access.domain.StoredAccount
import ru.creditcabinet.common.exception.UnauthorizedException
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.user.domain.UserStatus

internal fun AccountAccessPort.actor(claims: TokenClaims): StoredAccount {
    val account = account(claims.userId, true) ?: throw UnauthorizedException()
    if (account.user.status != UserStatus.ACTIVE || account.user.credentialsVersion != claims.credentialsVersion) throw UnauthorizedException()
    return account
}
