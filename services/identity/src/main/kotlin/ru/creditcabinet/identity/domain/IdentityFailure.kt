package ru.creditcabinet.identity.domain

/** Failures meaningful to Identity callers, mapped to transport responses at the boundary. */
sealed class IdentityFailure(override val message: String) : RuntimeException(message) {
    class InvalidInput(message: String) : IdentityFailure(message)
    class Unauthorized(message: String = "Требуется вход в систему") : IdentityFailure(message)
    class Forbidden(message: String) : IdentityFailure(message)
    class NotFound(message: String) : IdentityFailure(message)
    class Conflict(message: String) : IdentityFailure(message)
    class RateLimited(message: String) : IdentityFailure(message)
    class Unavailable(message: String) : IdentityFailure(message)
    class InvalidLink : IdentityFailure("Ссылка недействительна или срок её действия истёк. Запросите новую.")
}
