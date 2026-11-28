package ru.creditcabinet.auth.application

import ru.creditcabinet.user.domain.User
import java.util.UUID

data class IdentityActor(val userId: UUID, val credentialsVersion: Int, val companyId: UUID?, val isClient: Boolean)

data class IdentitySession(val token: String, val user: User)

interface SessionTokens {
    fun issue(user: User): String
    fun read(token: String): IdentityActor
}

interface SignInLimits {
    fun check(email: String, address: String)
    fun recordFailure(email: String)
    fun reset(email: String)
}

interface PasswordHashes {
    fun encode(password: String): String
    fun matches(password: String, hash: String): Boolean
}

/** Appends personal data consent for the edition published at /legal/consent. */
fun interface PersonalDataConsents {
    fun record(userId: UUID)
}
