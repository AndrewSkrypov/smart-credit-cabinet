package ru.creditcabinet.application.controller

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.creditcabinet.agent.application.AgentClientStatusUnavailable
import ru.creditcabinet.agent.domain.AgentAccessUnavailable
import ru.creditcabinet.common.dto.ErrorResponse
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.exception.GlobalExceptionHandler
import ru.creditcabinet.document.application.DocumentUploadsDisabled
import ru.creditcabinet.document.application.DocumentsUnavailable
import ru.creditcabinet.document.generated.application.GeneratedOriginalUnavailable
import ru.creditcabinet.financing.invoice.application.InvoicePdfTooLarge
import ru.creditcabinet.offer.application.SigningUnavailable

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class CreditBoundaryErrorHandler(private val errors: GlobalExceptionHandler) {
    @ExceptionHandler(
        AgentAccessUnavailable::class,
        AgentClientStatusUnavailable::class,
        DocumentUploadsDisabled::class,
        DocumentsUnavailable::class,
        GeneratedOriginalUnavailable::class,
        SigningUnavailable::class,
    )
    fun handle(exception: RuntimeException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        errors.handleApiException(ApiException(HttpStatus.SERVICE_UNAVAILABLE, checkNotNull(exception.message)), request)

    @ExceptionHandler(InvoicePdfTooLarge::class)
    fun invoiceTooLarge(exception: InvoicePdfTooLarge, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        errors.handleApiException(ApiException(HttpStatus.PAYLOAD_TOO_LARGE, checkNotNull(exception.message)), request)
}
