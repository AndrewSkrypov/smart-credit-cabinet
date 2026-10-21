package ru.creditcabinet.auth.application

import io.jsonwebtoken.Jwts
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Service
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.user.domain.User
import java.time.Duration
import java.time.Instant
import java.util.Date

@Service
class JwtService(
    @Value("\${cabinet.auth.jwt-secret}") secret: String,
    @Value("\${cabinet.auth.jwt-ttl-hours:12}") private val ttlHours: Long,
    environment: Environment,
) {
    // Secret validation and parsing are shared with every other service (platform-common).
    private val verifier = JwtVerifier(secret, environment)

    /**
     * Signs a token that carries the role, the company and the right to sign, so other services
     * know who is calling and for which company without asking identity on every request.
     */
    fun issue(user: User): String {
        val now = Instant.now()
        return Jwts.builder()
            .subject(user.id.toString())
            .claim(TokenClaims.ROLE, user.role.name)
            .claim(TokenClaims.FULL_NAME, user.fullName)
            .claim(TokenClaims.EMAIL, user.email)
            .apply { user.companyId?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
            .claim(TokenClaims.CAN_SIGN, user.canSign)
            .claim(TokenClaims.CREDENTIALS_VERSION, user.credentialsVersion)
            .issuedAt(Date.from(now))
            .expiration(Date.from(now.plus(Duration.ofHours(ttlHours))))
            .signWith(verifier.signingKey)
            .compact()
    }

    fun parseClaims(token: String): TokenClaims = verifier.parseClaims(token)
}
