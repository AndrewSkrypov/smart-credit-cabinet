package ru.creditcabinet.auth.domain

import java.time.Instant
import java.util.UUID

data class JuryLoginGrantClaim(val userId: UUID, val credentialsVersion: Int, val usedAt: Instant)

interface JuryLoginGrantPort {
    fun consume(tokenHash: String): JuryLoginGrantClaim?
}
