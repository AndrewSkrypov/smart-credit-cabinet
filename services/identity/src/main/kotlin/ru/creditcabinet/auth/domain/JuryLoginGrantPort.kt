package ru.creditcabinet.auth.domain

import ru.creditcabinet.user.domain.UserRole
import java.util.UUID

data class JuryLoginAccount(val fullName: String, val email: String, val role: UserRole)

interface JuryLoginGrantPort {
    fun inspect(tokenHash: String): JuryLoginAccount?
    fun consume(tokenHash: String): UUID?
}
