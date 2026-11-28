package ru.creditcabinet.agent.controller

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.agent.application.AgentClientStatusUnavailable
import ru.creditcabinet.agent.application.AgentMcpReads
import ru.creditcabinet.agent.domain.AgentAccessUnavailable
import ru.creditcabinet.agent.infrastructure.McpActorAssertionRedeemer
import ru.creditcabinet.application.application.CreditFailure
import ru.creditcabinet.financing.scenario.application.SavedPreviewNotApplicable
import ru.creditcabinet.financing.scenario.application.SavedPreviewNotReady
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.HexFormat
import java.util.UUID

@Hidden
@Profile("agent-mcp-remote")
@RestController
@RequestMapping("/internal/mcp/credit")
internal class AgentMcpRemoteController(
    private val reads: AgentMcpReads,
    private val actors: McpActorAssertionRedeemer,
    mapper: ObjectMapper,
    @Value("\${cabinet.mcp.credit-port:8443}") private val port: Int,
    @Value("\${cabinet.mcp.credit-tls.peer-sha256}") peerSha256: String,
) {
    private val pins = peerSha256.split(',').map(String::trim).map { pin ->
        require(pin.matches(Regex("[a-fA-F0-9]{64}")))
        HexFormat.of().parseHex(pin)
    }
    private val json = mapper.reader()
        .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
        .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    @GetMapping("/priorities")
    fun priorities(request: HttpServletRequest): Any = reply {
        val parameters = request.parameterMap
        val limit = if (parameters.isEmpty()) {
            10
        } else {
            val raw = parameters["limit"]?.singleOrNull()
            if (parameters.size != 1 || raw == null || !raw.matches(Regex("[0-9]{1,2}"))) throw InvalidRequest()
            raw.toInt().takeIf { it in 1..20 } ?: throw InvalidRequest()
        }
        reads.priorities(limit, verifiedActor(request))
    }

    @GetMapping("/applications/{id}/summary")
    fun summary(@PathVariable id: String, request: HttpServletRequest): Any = reply {
        if (request.parameterMap.isNotEmpty()) throw InvalidRequest()
        reads.summary(applicationId(id), verifiedActor(request))
    }

    @PostMapping("/applications/{id}/financing-scenario/preview", consumes = ["application/json"])
    fun preview(@PathVariable id: String, @RequestBody raw: String, request: HttpServletRequest): Any = reply {
        if (raw.length > 1024 || request.parameterMap.isNotEmpty()) throw InvalidRequest()
        val body: JsonNode = json.readTree(raw)
        if (!body.isObject ||
            body.fieldNames().asSequence().toSet() != setOf("mode", "delayDays", "alternativeTermMonths") ||
            body["mode"].textValue() != "SAVED_SCENARIO"
        ) {
            throw InvalidRequest()
        }
        val days = integer(body["delayDays"], 0..365)
        val alternative = integer(body["alternativeTermMonths"], 1..Int.MAX_VALUE)
        reads.preview(applicationId(id), days, alternative, verifiedActor(request))
    }

    private fun verifiedActor(request: HttpServletRequest): UUID {
        val peer = (request.getAttribute("jakarta.servlet.request.X509Certificate") as? Array<*>)?.firstOrNull() as? X509Certificate
        if (!request.isSecure || request.localPort != port || peer == null) throw CreditFailure.Forbidden("Недоверенный MCP транспорт")
        val digest = MessageDigest.getInstance("SHA-256").digest(peer.encoded)
        if (pins.none { MessageDigest.isEqual(it, digest) }) throw CreditFailure.Forbidden("Недоверенный MCP транспорт")
        if (request.getHeader("Authorization") != null || request.getHeader("Cookie") != null) {
            throw CreditFailure.Unauthenticated("Недопустимая MCP авторизация")
        }
        val header = request.getHeaders("X-Cabinet-Mcp-Actor-Assertion").toList().singleOrNull()
            ?: throw CreditFailure.Unauthenticated("Требуется MCP assertion")
        return actors.redeem(header)
    }

    private fun applicationId(value: String): UUID = try {
        UUID.fromString(value).also { if (!it.toString().equals(value, ignoreCase = true)) throw InvalidRequest() }
    } catch (_: IllegalArgumentException) {
        throw InvalidRequest()
    }

    private fun integer(value: JsonNode, range: IntRange): Int {
        if (!value.isIntegralNumber || !value.canConvertToInt() || value.intValue() !in range) throw InvalidRequest()
        return value.intValue()
    }

    private fun reply(action: () -> Any): ResponseEntity<Any> = try {
        ResponseEntity.ok(action())
    } catch (failure: CreditFailure) {
        when (failure) {
            is CreditFailure.NotFound -> error(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND")
            is CreditFailure.InvalidInput -> error(HttpStatus.BAD_REQUEST, "INVALID_INPUT")
            is CreditFailure.Unauthenticated -> error(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED")
            is CreditFailure.Forbidden -> error(HttpStatus.FORBIDDEN, "FORBIDDEN")
            is CreditFailure.Conflict -> error(HttpStatus.CONFLICT, "SCENARIO_NOT_APPLICABLE")
            is CreditFailure.DependencyFailure, is CreditFailure.DependencyUnavailable -> error(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE")
            is CreditFailure.RateLimited -> error(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED")
        }
    } catch (_: SavedPreviewNotReady) {
        error(HttpStatus.CONFLICT, "PREVIEW_NOT_READY")
    } catch (_: SavedPreviewNotApplicable) {
        error(HttpStatus.CONFLICT, "SCENARIO_NOT_APPLICABLE")
    } catch (_: InvalidRequest) {
        error(HttpStatus.BAD_REQUEST, "INVALID_INPUT")
    } catch (_: JsonProcessingException) {
        error(HttpStatus.BAD_REQUEST, "INVALID_INPUT")
    }

    @ExceptionHandler(AgentClientStatusUnavailable::class, AgentAccessUnavailable::class)
    fun unavailable(): ResponseEntity<Any> = error(HttpStatus.SERVICE_UNAVAILABLE, "DEPENDENCY_UNAVAILABLE")

    private fun error(status: HttpStatus, code: String): ResponseEntity<Any> = ResponseEntity.status(status).body(AgentMcpError(code))

    private class InvalidRequest : RuntimeException()
}
