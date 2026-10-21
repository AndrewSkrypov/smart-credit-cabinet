package ru.creditcabinet.common.exception

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.exc.InvalidFormatException
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingRequestHeaderException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import ru.creditcabinet.common.dto.ErrorResponse
import java.time.LocalDate
import java.time.temporal.Temporal
import java.util.UUID
import org.springframework.web.ErrorResponse as SpringErrorResponse

@RestControllerAdvice
class GlobalExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(ApiException::class)
    fun handleApiException(exception: ApiException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(exception.httpStatus).body(
            ErrorResponse(
                status = exception.httpStatus.value(),
                error = exception.httpStatus.reasonPhrase,
                message = exception.message,
                path = request.requestURI,
            ),
        )

    /** Two changes of the same object at once: the second one lost and must look at the current state. */
    @ExceptionHandler(OptimisticLockingFailureException::class)
    fun handleConcurrentChange(exception: OptimisticLockingFailureException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT).body(
            ErrorResponse(
                status = HttpStatus.CONFLICT.value(),
                error = HttpStatus.CONFLICT.reasonPhrase,
                message = "Данные только что изменились — обновите страницу и повторите действие",
                path = request.requestURI,
            ),
        )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidation(exception: MethodArgumentNotValidException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val message = exception.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        return badRequest(message.ifBlank { "Некорректный запрос" }, request)
    }

    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraint(exception: ConstraintViolationException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        badRequest(exception.message ?: "Некорректный запрос", request)

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(exception: IllegalArgumentException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        badRequest(exception.message ?: "Некорректный запрос", request)

    /** A body that is not JSON, or a field of the wrong type, an unknown enum value, a missing required field. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadable(exception: HttpMessageNotReadableException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        // The Jackson exception carries the field path; its own cause (e.g. a date parse error) does not.
        val cause = generateSequence<Throwable>(exception) { it.cause }.firstOrNull { it is JsonProcessingException }
        val message = when (cause) {
            is JsonParseException -> "Тело запроса — не корректный JSON"
            is MismatchedInputException -> {
                val field = cause.path.mapNotNull { it.fieldName ?: it.index.takeIf { index -> index >= 0 }?.toString() }.joinToString(".")
                when {
                    field.isEmpty() -> "Тело запроса не подходит по формату"
                    // Kotlin's non-null constructor parameter without a value, or an explicit null.
                    cause !is InvalidFormatException && cause.message.orEmpty().contains("null", ignoreCase = true) -> "Поле «$field» обязательно"
                    else -> "Поле «$field»: ${expected(cause.targetType)}"
                }
            }
            else -> if (exception.message.orEmpty().startsWith(
                    "Required request body is missing",
                )
            ) {
                "Нет тела запроса"
            } else {
                "Тело запроса не подходит по формату"
            }
        }
        return badRequest(message, request)
    }

    /** A path variable or query parameter that cannot be converted: "/users/abc", "?category=FOO", "?page=x". */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(exception: MethodArgumentTypeMismatchException, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val value = exception.value?.toString().orEmpty().take(MAX_ECHO)
        return badRequest("Параметр «${exception.name}»: ${expected(exception.requiredType)}, а передано «$value»", request)
    }

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParameter(exception: MissingServletRequestParameterException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        badRequest("Не указан параметр «${exception.parameterName}»", request)

    @ExceptionHandler(MissingRequestHeaderException::class)
    fun handleMissingHeader(exception: MissingRequestHeaderException, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        badRequest("Не указан заголовок «${exception.headerName}»", request)

    /** What a value of [type] looks like, in words a person can act on. */
    private fun expected(type: Class<*>?): String =
        when {
            type == null -> "значение не подходит по типу"
            type == UUID::class.java -> "нужен идентификатор вида 3f2b8c1e-…-…, как в ссылке на объект"
            type.isEnum -> {
                val values = type.enumConstants.map { it.toString() }
                if (values.size <= MAX_ENUM_VALUES) "допустимые значения — ${values.joinToString(", ")}" else "недопустимое значение"
            }
            type == Int::class.java || type == Integer::class.java || type == Long::class.java || type == java.lang.Long::class.java -> "нужно целое число"
            type == Double::class.java || type == java.lang.Double::class.java || Number::class.java.isAssignableFrom(type) -> "нужно число"
            type == Boolean::class.java || type == java.lang.Boolean::class.java -> "нужно true или false"
            type == LocalDate::class.java -> "нужна дата в формате ГГГГ-ММ-ДД"
            Temporal::class.java.isAssignableFrom(type) -> "нужны дата и время в формате ISO 8601, например 2026-09-30T10:00:00Z"
            else -> "значение не подходит по типу"
        }

    /**
     * Everything else. Spring's own errors (unknown path, wrong method, too large upload...)
     * keep their status with a plain message; anything unexpected is a 500 that is logged
     * with its stack trace and answered without internals, but with the request id.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(exception: Exception, request: HttpServletRequest): ResponseEntity<ErrorResponse> {
        val status = when (exception) {
            is SpringErrorResponse -> HttpStatus.resolve(exception.statusCode.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR
            is AccessDeniedException -> HttpStatus.FORBIDDEN
            else -> HttpStatus.INTERNAL_SERVER_ERROR
        }
        if (status.is5xxServerError) {
            log.error("Unhandled error on {} {}", request.method, request.requestURI, exception)
        }
        val message = when (status) {
            HttpStatus.NOT_FOUND -> "Не найдено"
            HttpStatus.METHOD_NOT_ALLOWED -> "Метод не поддерживается"
            HttpStatus.PAYLOAD_TOO_LARGE -> "Слишком большой запрос или файл"
            HttpStatus.FORBIDDEN -> "Недостаточно прав для этого действия"
            else -> if (status.is5xxServerError) "Внутренняя ошибка сервиса. Если повторится, сообщите код запроса." else "Некорректный запрос"
        }
        return ResponseEntity.status(status).body(
            ErrorResponse(
                status = status.value(),
                error = status.reasonPhrase,
                message = message,
                path = request.requestURI,
            ),
        )
    }

    private companion object {
        const val MAX_ECHO = 60
        const val MAX_ENUM_VALUES = 12
    }

    private fun badRequest(message: String, request: HttpServletRequest): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
            ErrorResponse(
                status = HttpStatus.BAD_REQUEST.value(),
                error = HttpStatus.BAD_REQUEST.reasonPhrase,
                message = message,
                path = request.requestURI,
            ),
        )
}
