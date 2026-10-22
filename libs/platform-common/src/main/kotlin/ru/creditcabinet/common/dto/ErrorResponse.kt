package ru.creditcabinet.common.dto

import io.swagger.v3.oas.annotations.media.Schema
import ru.creditcabinet.common.observability.RequestIds
import java.time.Instant

@Schema(description = "Ошибка API")
data class ErrorResponse(
    val timestamp: Instant = Instant.now(),
    @field:Schema(description = "HTTP-код ошибки", example = "404")
    val status: Int,
    @field:Schema(description = "Название HTTP-статуса", example = "Not Found")
    val error: String,
    @field:Schema(description = "Понятное объяснение ошибки", example = "Заявка не найдена")
    val message: String,
    @field:Schema(description = "Путь запроса, который завершился ошибкой", example = "/api/v1/applications/00000000-0000-0000-0000-000000000001")
    val path: String,
    /** Quote this to support: the same id is in the logs of every service involved. */
    val requestId: String? = RequestIds.current(),
)
