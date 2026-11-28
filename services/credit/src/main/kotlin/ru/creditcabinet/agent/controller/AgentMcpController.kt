package ru.creditcabinet.agent.controller

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.agent.application.AgentClientStatusUnavailable
import ru.creditcabinet.agent.application.AgentMcpPreview
import ru.creditcabinet.agent.application.AgentMcpPriorities
import ru.creditcabinet.agent.application.AgentMcpReads
import ru.creditcabinet.agent.application.AgentMcpSummary
import ru.creditcabinet.agent.domain.AgentAccessUnavailable
import ru.creditcabinet.application.application.CreditFailure
import ru.creditcabinet.financing.scenario.application.SavedPreviewNotApplicable
import ru.creditcabinet.financing.scenario.application.SavedPreviewNotReady
import java.util.UUID

/** Only the isolated synthetic listener opts into this controller. Existing Credit auth still applies. */
@Profile("agent-mcp-local")
@RestController
@RequestMapping("/api/v1/agent")
internal class AgentMcpController(
    private val reads: AgentMcpReads,
    mapper: ObjectMapper,
    @Value("\${server.address:}") bindAddress: String,
    @Value("\${server.forward-headers-strategy:none}") forwardedHeaders: String,
) {
    init {
        require(bindAddress == "127.0.0.1" && forwardedHeaders.equals("none", ignoreCase = true)) {
            "Agent MCP local routes require IPv4 loopback binding without forwarded headers"
        }
    }

    private val json = mapper.reader()
        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
    private val previewFields = setOf("mode", "delayDays", "alternativeTermMonths")
    private val uuidPattern = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    @Operation(parameters = [Parameter(name = "limit", schema = Schema(type = "integer", minimum = "1", maximum = "20", defaultValue = "10"))])
    @GetMapping("/mcp/priorities")
    fun priorities(request: HttpServletRequest): AgentMcpPriorities {
        requireLoopback(request)
        val parameters = request.parameterMap
        val limit = if (parameters.isEmpty()) {
            10
        } else {
            val raw = parameters["limit"]?.singleOrNull()
            if (parameters.size != 1 || raw == null || !raw.matches(Regex("[0-9]{1,2}"))) throw InvalidRequest()
            raw.toInt().takeIf { it in 1..20 } ?: throw InvalidRequest()
        }
        return reads.priorities(limit)
    }

    @GetMapping("/mcp/applications/{id}/summary")
    fun summary(
        @Parameter(schema = Schema(type = "string", format = "uuid"))
        @PathVariable id: String,
        request: HttpServletRequest,
    ): AgentMcpSummary {
        requireLoopback(request)
        if (request.parameterMap.isNotEmpty()) throw InvalidRequest()
        return reads.summary(parseId(id))
    }

    @Operation(
        requestBody = io.swagger.v3.oas.annotations.parameters.RequestBody(
            required = true,
            content = [Content(mediaType = "application/json", schema = Schema(implementation = AgentMcpPreviewRequest::class))],
        ),
    )
    @PostMapping("/mcp/applications/{id}/financing-scenario/preview", consumes = ["application/json"])
    fun preview(
        @Parameter(schema = Schema(type = "string", format = "uuid"))
        @PathVariable id: String,
        @RequestBody body: String,
        request: HttpServletRequest,
    ): AgentMcpPreview {
        requireLoopback(request)
        if (request.parameterMap.isNotEmpty() || body.length > 1024) throw InvalidRequest()
        val input: JsonNode = json.readTree(body)
        if (!input.isObject ||
            input.fieldNames().asSequence().toSet() != previewFields ||
            input["mode"].textValue() != "SAVED_SCENARIO"
        ) {
            throw InvalidRequest()
        }
        return reads.preview(parseId(id), integer(input["delayDays"], 0..365), integer(input["alternativeTermMonths"], 1..Int.MAX_VALUE))
    }

    @ExceptionHandler
    fun error(failure: CreditFailure): ResponseEntity<AgentMcpError> = when (failure) {
        is CreditFailure.NotFound -> errorResponse(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND")
        is CreditFailure.InvalidInput -> invalidRequest()
        is CreditFailure.Unauthenticated -> errorResponse(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
        is CreditFailure.Forbidden -> errorResponse(HttpStatus.FORBIDDEN, "FORBIDDEN")
        is CreditFailure.Conflict -> notApplicable()
        is CreditFailure.DependencyFailure, is CreditFailure.DependencyUnavailable -> unavailable()
        is CreditFailure.RateLimited -> errorResponse(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED")
    }

    @ExceptionHandler(SavedPreviewNotReady::class)
    fun notReady(): ResponseEntity<AgentMcpError> = errorResponse(HttpStatus.CONFLICT, "PREVIEW_NOT_READY")

    @ExceptionHandler(SavedPreviewNotApplicable::class)
    fun notApplicable(): ResponseEntity<AgentMcpError> = errorResponse(HttpStatus.CONFLICT, "SCENARIO_NOT_APPLICABLE")

    @ExceptionHandler(AgentAccessUnavailable::class, AgentClientStatusUnavailable::class)
    fun unavailable(): ResponseEntity<AgentMcpError> = errorResponse(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE")

    @ExceptionHandler(InvalidRequest::class, JsonProcessingException::class, HttpMessageNotReadableException::class)
    fun invalidRequest(): ResponseEntity<AgentMcpError> = errorResponse(HttpStatus.BAD_REQUEST, "INVALID_INPUT")

    private fun requireLoopback(request: HttpServletRequest) {
        if (request.remoteAddr != "127.0.0.1") throw CreditFailure.Forbidden("Только локальный доступ")
    }

    private fun parseId(id: String): UUID {
        if (!uuidPattern.matches(id)) throw InvalidRequest()
        return UUID.fromString(id)
    }

    private fun integer(node: JsonNode, range: IntRange): Int {
        if (!node.isIntegralNumber || !node.canConvertToInt() || node.intValue() !in range) throw InvalidRequest()
        return node.intValue()
    }

    private fun errorResponse(status: HttpStatus, code: String): ResponseEntity<AgentMcpError> =
        ResponseEntity.status(status).body(AgentMcpError(code))

    private class InvalidRequest : RuntimeException()
}

internal data class AgentMcpError(val code: String)

@Schema(additionalProperties = Schema.AdditionalPropertiesValue.FALSE)
internal data class AgentMcpPreviewRequest(
    @field:Schema(allowableValues = ["SAVED_SCENARIO"], requiredMode = Schema.RequiredMode.REQUIRED)
    val mode: String,
    @field:Schema(minimum = "0", maximum = "365", requiredMode = Schema.RequiredMode.REQUIRED)
    val delayDays: Int,
    @field:Schema(minimum = "1", requiredMode = Schema.RequiredMode.REQUIRED)
    val alternativeTermMonths: Int,
)
