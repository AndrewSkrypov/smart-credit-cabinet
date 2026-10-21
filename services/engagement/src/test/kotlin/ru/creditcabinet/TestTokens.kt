package ru.creditcabinet

import io.jsonwebtoken.Jwts
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.TokenClaims
import java.util.Date
import java.util.UUID

/** Tokens as identity signs them, for tests of a service that only verifies them. */
fun JwtVerifier.tokenFor(userId: UUID, role: String, companyId: UUID? = null): String =
    Jwts.builder().subject(userId.toString()).claim(TokenClaims.ROLE, role)
        .apply { companyId?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
        .expiration(Date(System.currentTimeMillis() + 3_600_000)).signWith(signingKey).compact()
