package ru.creditcabinet.assistant

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
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

@Tag(name = "Кредитный помощник")
@RestController
@RequestMapping("/api/v1/assistant")
class AssistantController(private val service: AssistantService) {
    @Operation(summary = "Режим помощника: модель с открытыми весами или справочные ответы по правилам")
    @GetMapping("/status")
    fun status(): AssistantStatus = service.status().let { (mode, model) -> AssistantStatus(mode, model) }

    @Operation(summary = "Вопрос помощнику о продуктах, статусе и решении по заявке")
    @PostMapping("/messages")
    fun ask(
        @Valid @RequestBody request: AssistantQuestion,
        @RequestHeader(HttpHeaders.AUTHORIZATION) authorization: String,
    ): Answer = service.answer(request.question.trim(), request.applicationId, request.history, authorization)
}
