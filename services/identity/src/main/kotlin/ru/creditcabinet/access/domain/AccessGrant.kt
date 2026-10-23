package ru.creditcabinet.access.domain

import org.springframework.http.HttpStatus
import ru.creditcabinet.common.exception.ApiException
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

enum class LinkPurpose(val path: String) { PASSWORD_RESET("/reset-password"), COMPANY_INVITATION("/accept-invitation") }

data class AccessGrant(
    val id: UUID,
    val tokenHash: String,
    val expiresAt: Instant,
    val usedAt: Instant?,
    val revokedAt: Instant?,
) {
    fun available(now: Instant): Boolean = usedAt == null && revokedAt == null && now.isBefore(expiresAt)

    fun requireValid(token: String, now: Instant) {
        if (!available(now) || !MessageDigest.isEqual(tokenHash.toByteArray(), hash(token).toByteArray())) expiredLink()
    }

    companion object {
        fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        fun tokenId(token: String): UUID = runCatching {
            require(token.length in 70..100 && token.count { it == '.' } == 1)
            UUID.fromString(token.substringBefore('.'))
        }.getOrElse { expiredLink() }

        fun expiredLink(): Nothing = throw ApiException(HttpStatus.GONE, "Ссылка недействительна или срок её действия истёк. Запросите новую.")
    }
}
