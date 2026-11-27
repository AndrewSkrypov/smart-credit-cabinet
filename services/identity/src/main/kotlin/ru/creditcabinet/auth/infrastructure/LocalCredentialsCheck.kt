package ru.creditcabinet.auth.infrastructure

import org.springframework.stereotype.Component
import ru.creditcabinet.common.exception.UnauthorizedException
import ru.creditcabinet.common.security.CredentialsCheck
import ru.creditcabinet.user.application.UserQueries
import java.util.UUID

/** Identity checks revoked tokens against its own database, strictly and without a cache. */
@Component
class LocalCredentialsCheck(private val users: UserQueries) : CredentialsCheck {
    override fun currentVersion(userId: UUID): Int = users.activeCredentialsVersion(userId)
        ?: throw UnauthorizedException("Сессия больше не действует — войдите заново")
}
