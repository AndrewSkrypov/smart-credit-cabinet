package ru.creditcabinet.assistant.controller

import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.assistant.application.AssistantFailure
import ru.creditcabinet.assistant.application.AssistantFailureReason
import ru.creditcabinet.assistant.application.AssistantNextSteps
import ru.creditcabinet.assistant.application.AssistantService
import ru.creditcabinet.assistant.application.AssistantUiFlags
import ru.creditcabinet.assistant.application.HintType
import ru.creditcabinet.assistant.domain.AnswerMode
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.security.CurrentCaller
import java.io.OutputStream
import java.time.Instant
import java.util.UUID
import ru.creditcabinet.assistant.application.AssistantHint as NextStep
import ru.creditcabinet.assistant.domain.Answer as AssistantAnswer
import ru.creditcabinet.assistant.domain.AnswerLink as AssistantLink
import ru.creditcabinet.assistant.domain.ReferencePreview as AssistantReference
import ru.creditcabinet.assistant.domain.Turn as ConversationTurn

data class AssistantQuestion(
    @field:NotBlank(message = "Задайте вопрос")
    @field:Size(max = AssistantService.MAX_QUESTION, message = "Вопрос — до 500 символов")
    val question: String,
    /** The application the question is about; its facts are read with the caller's own rights. */
    val applicationId: UUID? = null,
    @field:Size(max = 10)
    val history: List<Turn> = emptyList(),
)

data class Turn(val role: String, val text: String) {
    internal fun toTurn() = ConversationTurn(role, text)
}

data class Answer(val text: String, val mode: AnswerMode, val sources: List<String>, val link: AnswerLink? = null)

data class AnswerLink(val label: String, val path: String)

data class ReferencePreview(val text: String, val source: String)

data class AssistantHint(
    val type: HintType,
    val title: String,
    val detail: String,
    val applicationId: UUID? = null,
    val deadline: Instant? = null,
    val canRemind: Boolean = false,
    val companyId: UUID? = null,
    val infoRequestId: UUID? = null,
)

private fun AssistantAnswer.toResponse() = Answer(text, mode, sources, link?.toResponse())

private fun AssistantLink.toResponse() = AnswerLink(label, path)

private fun AssistantReference.toResponse() = ReferencePreview(text, source)

private fun NextStep.toResponse() = AssistantHint(type, title, detail, applicationId, deadline, canRemind, companyId, infoRequestId)

data class AssistantStatus(val mode: AnswerMode, val model: String?)

data class AssistantUiConfig(val keshaEnabled: Boolean)

data class SummaryRequest(val applicationId: UUID)

data class ReminderRequest(val applicationId: UUID, val infoRequestId: UUID)

@Tag(name = "Кредитный помощник")
@RestController
@RequestMapping("/api/v1/assistant")
class AssistantController(
    private val service: AssistantService,
    private val nextSteps: AssistantNextSteps,
    private val mapper: ObjectMapper,
    private val uiFlags: AssistantUiFlags,
) {
    @Operation(summary = "Режим помощника: модель с открытыми весами или справочные ответы по правилам")
    @GetMapping("/status")
    fun status(): AssistantStatus = service.status().let { (mode, model) -> AssistantStatus(mode, model) }

    @Operation(summary = "Конфигурация интерфейса помощника")
    @GetMapping("/ui-config")
    fun uiConfig(): ResponseEntity<AssistantUiConfig> {
        val caller = CurrentCaller.claims()
        val enabled = (caller.isClient || caller.isAgent) && uiFlags.isKeshaEnabled(caller.userId)
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(AssistantUiConfig(enabled))
    }

    @Operation(summary = "Вопрос помощнику о продуктах, статусе и решении по заявке")
    @PostMapping("/messages")
    fun ask(
        @Valid @RequestBody request: AssistantQuestion,
        @RequestHeader(HttpHeaders.AUTHORIZATION) authorization: String,
    ): Answer = assistantResponse {
        service.answer(request.question.trim(), request.applicationId, request.history.map(Turn::toTurn), authorization, CurrentCaller.claims()).toResponse()
    }

    @Operation(summary = "Ход проверки и проверенный ответ помощника в потоке событий")
    @PostMapping("/messages/stream", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun stream(
        @Valid @RequestBody request: AssistantQuestion,
        @RequestHeader(HttpHeaders.AUTHORIZATION) authorization: String,
        response: HttpServletResponse,
    ) {
        response.contentType = MediaType.TEXT_EVENT_STREAM_VALUE
        response.characterEncoding = Charsets.UTF_8.name()
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        response.setHeader("X-Accel-Buffering", "no")
        val output = response.outputStream
        output.event("status", mapOf("text" to "Проверяю справку и данные кабинета…"))
        try {
            val answer = assistantResponse {
                service.answer(request.question.trim(), request.applicationId, request.history.map(Turn::toTurn), authorization, CurrentCaller.claims()) {
                    output.event("reference", it.toResponse())
                }
            }
            // Model text crosses the network only after the complete answer has passed grounding.
            output.event("answer", answer.toResponse())
        } catch (exception: ApiException) {
            output.event("failure", mapOf("status" to exception.httpStatus.value(), "message" to exception.message))
        }
    }

    private fun OutputStream.event(name: String, data: Any) {
        write("event: $name\ndata: ${mapper.writeValueAsString(data)}\n\n".toByteArray(Charsets.UTF_8))
        flush()
    }

    @Operation(summary = "Сводка для андеррайтера: пересказ рекомендаций экспертной системы по заявке (только сотрудники банка)")
    @PostMapping("/underwriting-summary")
    fun underwritingSummary(
        @RequestBody request: SummaryRequest,
        @RequestHeader(HttpHeaders.AUTHORIZATION) authorization: String,
    ): Answer = assistantResponse {
        service.underwritingSummary(request.applicationId, authorization, CurrentCaller.claims()).toResponse()
    }

    @Operation(summary = "Актуальные подсказки по заявкам и портфелю агента")
    @GetMapping("/next-steps")
    fun nextSteps(
        @RequestParam(required = false) applicationId: UUID?,
        @RequestHeader(HttpHeaders.AUTHORIZATION) authorization: String,
    ): List<AssistantHint> = assistantResponse {
        nextSteps.list(applicationId, authorization, CurrentCaller.claims()).map(NextStep::toResponse)
    }

    @Operation(summary = "Отправить клиенту подтверждённое напоминание о документах")
    @PostMapping("/reminders")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remind(@RequestBody request: ReminderRequest, @RequestHeader(HttpHeaders.AUTHORIZATION) authorization: String) = assistantResponse {
        nextSteps.sendReminder(request.applicationId, request.infoRequestId, authorization, CurrentCaller.claims())
    }
}

internal fun <T> assistantResponse(block: () -> T): T = try {
    block()
} catch (failure: AssistantFailure) {
    val status = when (failure.reason) {
        AssistantFailureReason.UNAUTHENTICATED -> HttpStatus.UNAUTHORIZED
        AssistantFailureReason.FORBIDDEN -> HttpStatus.FORBIDDEN
        AssistantFailureReason.NOT_FOUND -> HttpStatus.NOT_FOUND
        AssistantFailureReason.CONFLICT -> HttpStatus.CONFLICT
        AssistantFailureReason.RATE_LIMIT -> HttpStatus.TOO_MANY_REQUESTS
        AssistantFailureReason.UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE
        AssistantFailureReason.INVALID_EVENT -> HttpStatus.BAD_REQUEST
    }
    throw ApiException(status, failure.message)
}
