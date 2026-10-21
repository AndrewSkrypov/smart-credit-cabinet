package ru.creditcabinet.common.dto

import io.swagger.v3.oas.annotations.media.Schema
import ru.creditcabinet.common.observability.RequestIds
import java.time.Instant

@Schema(description = "Ошибка API")
data class ErrorResponse(
    val timestamp: Instant = Instant.now(),
    val status: Int,
    val error: String,
    val message: String,
    val path: String,
    /** Quote this to support: the same id is in the logs of every service involved. */
    val requestId: String? = RequestIds.current(),
)
