package ru.creditcabinet.product.controller

import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import ru.creditcabinet.common.dto.ErrorResponse
import ru.creditcabinet.common.exception.GlobalExceptionHandler
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.product.domain.ProductOutsideSubmissionCatalog

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice
class SubmittedProductErrorHandler(private val errors: GlobalExceptionHandler) {
    @ExceptionHandler(ProductOutsideSubmissionCatalog::class)
    fun productNotFound(exception: ProductOutsideSubmissionCatalog, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        errors.handleApiException(NotFoundException(checkNotNull(exception.message)), request)
}
