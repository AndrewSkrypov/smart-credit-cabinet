package ru.creditcabinet.application.infrastructure

import org.springframework.http.HttpStatus
import ru.creditcabinet.application.application.CreditFailure
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.limits.SharedLimitExceeded

/** Translates the failure contract at a legacy adapter boundary before returning to Credit. */
internal inline fun <T> creditPortCall(action: () -> T): T = try {
    action()
} catch (failure: ApiException) {
    throw creditPortFailure(failure)
}

internal fun creditPortFailure(failure: ApiException): CreditFailure = when (failure.httpStatus) {
    HttpStatus.BAD_REQUEST -> CreditFailure.InvalidInput(failure.message)
    HttpStatus.UNAUTHORIZED -> CreditFailure.Unauthenticated(failure.message)
    HttpStatus.FORBIDDEN -> CreditFailure.Forbidden(failure.message)
    HttpStatus.NOT_FOUND -> CreditFailure.NotFound(failure.message)
    HttpStatus.CONFLICT -> CreditFailure.Conflict(failure.message)
    HttpStatus.TOO_MANY_REQUESTS -> CreditFailure.RateLimited(failure.message, (failure as? SharedLimitExceeded)?.retryAfterSeconds)
    HttpStatus.BAD_GATEWAY -> CreditFailure.DependencyFailure(failure.message)
    HttpStatus.SERVICE_UNAVAILABLE -> CreditFailure.DependencyUnavailable(failure.message)
    else -> error("Unexpected status at Credit port: ${failure.httpStatus.value()}")
}
