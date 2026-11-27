package ru.creditcabinet.identity.controller

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.exception.GlobalExceptionHandler
import ru.creditcabinet.identity.domain.IdentityFailure

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class IdentityErrors(private val errors: GlobalExceptionHandler) {
    @ExceptionHandler(IdentityFailure::class)
    fun handle(failure: IdentityFailure, request: HttpServletRequest) = errors.handleApiException(
        ApiException(
            when (failure) {
                is IdentityFailure.InvalidInput -> HttpStatus.BAD_REQUEST
                is IdentityFailure.Unauthorized -> HttpStatus.UNAUTHORIZED
                is IdentityFailure.Forbidden -> HttpStatus.FORBIDDEN
                is IdentityFailure.NotFound -> HttpStatus.NOT_FOUND
                is IdentityFailure.Conflict -> HttpStatus.CONFLICT
                is IdentityFailure.RateLimited -> HttpStatus.TOO_MANY_REQUESTS
                is IdentityFailure.Unavailable -> HttpStatus.SERVICE_UNAVAILABLE
                is IdentityFailure.InvalidLink -> HttpStatus.GONE
            },
            failure.message,
        ),
        request,
    )
}
