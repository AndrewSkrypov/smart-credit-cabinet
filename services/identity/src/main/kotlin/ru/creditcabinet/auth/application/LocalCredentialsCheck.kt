package ru.creditcabinet.auth.application

import org.springframework.stereotype.Component
import ru.creditcabinet.common.security.CredentialsCheck
import ru.creditcabinet.user.domain.UserDataPort
import java.util.UUID

/** Identity checks revoked tokens against its own database, strictly and without a cache. */
@Component
class LocalCredentialsCheck(private val users: UserDataPort) : CredentialsCheck {
    override fun currentVersion(userId: UUID): Int? = users.findById(userId)?.credentialsVersion
}
