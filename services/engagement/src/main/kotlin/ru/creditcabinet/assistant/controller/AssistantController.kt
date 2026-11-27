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
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.assistant.application.Answer
import ru.creditcabinet.assistant.application.AnswerMode
import ru.creditcabinet.assistant.application.AssistantHint
import ru.creditcabinet.assistant.application.AssistantNextSteps
import ru.creditcabinet.assistant.application.AssistantService
import ru.creditcabinet.assistant.application.Turn
import ru.creditcabinet.common.exception.ApiException
import java.io.OutputStream
import java.util.UUID

data class AssistantQuestion(
    @field:NotBlank(message = "Задайте вопрос")
    @field:Size(max = AssistantService.MAX_QUESTION, message = "Вопрос — до 500 символов")
    val question: String,
    /** The application the question is about; its facts are read with the caller's own rights. */
    val applicationId: UUID? = null,
    @field:Size(max = 10)
    val history: List<Turn> = emptyList(),
)

data class AssistantStatus(val mode: AnswerMode, val model: String?)

data class SummaryRequest(val applicationId: UUID)

data class ReminderRequest(val applicationId: UUID, val infoRequestId: UUID)

@Tag(name = "Кредитный помощник")
@RestController
@RequestMapping("/api/v1/assistant")
class AssistantController(
    private val service: AssistantService,
    private val nextSteps: AssistantNextSteps,
    private val mapper: ObjectMapper,
) {
    @Operation(summary = "Режим помощника: модель с открытыми весами или справочные ответы по правилам")
    @GetMapping("/status")
    fun status(): AssistantStatus = service.status().let { (mode, model) -> AssistantStatus(mode, model) }

    @Operation(summary = "Вопрос помощнику о продуктах, статусе и решении по заявке")
    @PostMapping("/messages")
    fun ask(
        @Valid @RequestBody request: AssistantQuestion,
        @RequestHeader(HttpHeaders.AUTHORIZATION) authorization: String,
    ): Answer = service.answer(request.question.trim(), request.applicationId, request.history, authorization)

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
            val answer = service.answer(request.question.trim(), request.applicationId, request.history, authorization) { reference ->
                output.event("reference", reference)
            }
            // Model text crosses the network only after the complete answer has passed grounding.
            output.event("answer", answer)
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
    ): Answer = service.underwritingSummary(request.applicationId, authorization)

    @Operation(summary = "Актуальные подсказки по заявкам и портфелю агента")
    @GetMapping("/next-steps")
    fun nextSteps(
        @RequestParam(required = false) applicationId: UUID?,
        @RequestHeader(HttpHeaders.AUTHORIZATION) authorization: String,
    ): List<AssistantHint> = nextSteps.list(applicationId, authorization)

    @Operation(summary = "Отправить клиенту подтверждённое напоминание о документах")
    @PostMapping("/reminders")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun remind(@RequestBody request: ReminderRequest, @RequestHeader(HttpHeaders.AUTHORIZATION) authorization: String) =
        nextSteps.sendReminder(request.applicationId, request.infoRequestId, authorization)
}
