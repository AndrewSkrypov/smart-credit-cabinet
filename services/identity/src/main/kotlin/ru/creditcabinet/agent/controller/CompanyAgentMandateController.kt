package ru.creditcabinet.agent.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.agent.application.CompanyAgentMandates
import ru.creditcabinet.agent.domain.AgentMandateStatus
import ru.creditcabinet.agent.domain.CompanyAgentMandate
import ru.creditcabinet.common.security.CurrentCaller
import java.time.Instant
import java.util.UUID

data class IssueAgentMandateRequest(val agentUserId: UUID, val expiresAt: Instant)

data class AgentMandateResponse(
    val id: UUID,
    val agentUserId: UUID,
    val agentName: String,
    val status: AgentMandateStatus,
    val assignedAt: Instant,
    val expiresAt: Instant?,
    val revokedAt: Instant?,
)

@Tag(name = "Компания клиента")
@RestController
@RequestMapping("/api/v1/companies/me/agent-mandates")
class CompanyAgentMandateController(private val mandates: CompanyAgentMandates) {
    @Operation(summary = "История агентских мандатов текущей компании")
    @GetMapping
    fun list(): List<AgentMandateResponse> = mandates.list(CurrentCaller.claims()).map(CompanyAgentMandate::toResponse)

    @Operation(summary = "Выдать агенту мандат от имени текущей компании")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun issue(@RequestBody request: IssueAgentMandateRequest): AgentMandateResponse =
        mandates.issue(CurrentCaller.claims(), request.agentUserId, request.expiresAt).toResponse()

    @Operation(summary = "Отозвать агентский мандат текущей компании")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(@PathVariable id: UUID) = mandates.revoke(CurrentCaller.claims(), id)
}

private fun CompanyAgentMandate.toResponse() = AgentMandateResponse(
    mandate.id,
    mandate.agentUserId,
    agentName,
    mandate.status,
    mandate.assignedAt,
    mandate.expiresAt,
    mandate.revokedAt,
)
