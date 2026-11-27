package ru.creditcabinet.application.application

sealed class CreditFailure(override val message: String) : RuntimeException(message) {
    class InvalidInput(message: String) : CreditFailure(message)

    class Unauthenticated(message: String) : CreditFailure(message)

    class Forbidden(message: String) : CreditFailure(message)

    class NotFound(message: String) : CreditFailure(message)

    class Conflict(message: String) : CreditFailure(message)

    class RateLimited(message: String, val retryAfterSeconds: Long? = null) : CreditFailure(message)

    class DependencyFailure(message: String) : CreditFailure(message)

    class DependencyUnavailable(message: String) : CreditFailure(message)
}
