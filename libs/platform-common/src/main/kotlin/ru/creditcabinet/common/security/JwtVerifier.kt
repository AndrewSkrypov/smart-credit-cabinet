package ru.creditcabinet.common.security

import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.core.env.Environment
import org.springframework.http.HttpStatus
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.exception.ForbiddenException
import ru.creditcabinet.common.exception.UnauthorizedException
import java.util.UUID
import javax.crypto.SecretKey

/**
 * Checks platform JWTs locally, so a service can tell who is calling without asking the
 * service that issued the token. Every service shares the signing secret (JWT_SECRET).
 */
class JwtVerifier(
    secret: String,
    environment: Environment,
    private val credentialsCheck: CredentialsCheck? = null,
    private val revocations: RevocationWatermarks? = null,
) {
    init {
        val isProd = environment.activeProfiles.contains("prod")
        require(!isProd || secret != DEV_DEFAULT_SECRET) {
            "JWT_SECRET is still the dev placeholder while running with the 'prod' profile. " +
                "Set a real secret (openssl rand -base64 48) before deploying."
        }
        require(secret.toByteArray(Charsets.UTF_8).size >= 32) {
            "JWT_SECRET must be at least 32 bytes for HS256."
        }
    }

    val signingKey: SecretKey = Keys.hmacShaKeyFor(secret.toByteArray(Charsets.UTF_8))

    fun parseUserId(token: String): UUID = parseClaims(token).userId

    fun parseClaims(token: String): TokenClaims {
        val claims = try {
            Jwts.parser().verifyWith(signingKey).build().parseSignedClaims(token).payload
        } catch (exception: Exception) {
            throw UnauthorizedException("Недействительный или истёкший токен")
        }
        val parsed = TokenClaims(
            userId = UUID.fromString(claims.subject),
            role = claims.get(TokenClaims.ROLE, String::class.java),
            fullName = claims.get(TokenClaims.FULL_NAME, String::class.java),
            email = claims.get(TokenClaims.EMAIL, String::class.java),
            companyId = claims.get(TokenClaims.COMPANY_ID, String::class.java)?.let(UUID::fromString),
            canSign = claims.get(TokenClaims.CAN_SIGN, Boolean::class.javaObjectType) ?: false,
            credentialsVersion = claims.get(TokenClaims.CREDENTIALS_VERSION, Integer::class.java)?.toInt() ?: 0,
        )
        if (revocations?.isOlder(parsed.userId, parsed.credentialsVersion) == true) {
            throw UnauthorizedException("Сессия больше не действует — войдите заново")
        }
        val current = try {
            credentialsCheck?.currentVersion(parsed.userId)
        } catch (exception: ApiException) {
            throw exception
        } catch (_: Exception) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Проверка сессии временно недоступна — попробуйте позже")
        }
        if (credentialsCheck != null && current == null) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Проверка сессии временно недоступна — попробуйте позже")
        }
        if (current != null && parsed.credentialsVersion != current) {
            revocations?.observe(parsed.userId, current)
            throw UnauthorizedException("Сессия больше не действует — войдите заново")
        }
        return parsed
    }

    /** User id from an `Authorization: Bearer <token>` header; 401 when absent or invalid. */
    fun requireUserId(authorization: String?): UUID = parseClaims(bearerToken(authorization)).userId

    /** All claims from an `Authorization: Bearer <token>` header; 401 when absent or invalid. */
    fun requireClaims(authorization: String?): TokenClaims = parseClaims(bearerToken(authorization))

    /**
     * Claims of a caller that has one of [roles]; 401 without a valid token, 403 with another
     * role. Role checks belong on the server: the UI hiding a button protects nothing.
     */
    fun requireRole(authorization: String?, vararg roles: String): TokenClaims {
        val claims = requireClaims(authorization)
        if (claims.role !in roles) throw ForbiddenException("Недостаточно прав для этого действия")
        return claims
    }

    private fun bearerToken(authorization: String?): String =
        authorization?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() }
            ?: throw UnauthorizedException()

    companion object {
        const val DEV_DEFAULT_SECRET = "dev-only-insecure-secret-change-me-32bytes+"
    }
}
