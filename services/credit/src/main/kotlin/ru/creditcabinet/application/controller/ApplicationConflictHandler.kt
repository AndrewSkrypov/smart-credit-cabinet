package ru.creditcabinet.application.controller

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.creditcabinet.application.domain.ApplicationConflictException
import ru.creditcabinet.common.dto.ErrorResponse
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.GlobalExceptionHandler

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class ApplicationConflictHandler(private val errors: GlobalExceptionHandler) {
    @ExceptionHandler(ApplicationConflictException::class)
    fun handle(exception: ApplicationConflictException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        errors.handleApiException(ConflictException(exception.message), request)
}
