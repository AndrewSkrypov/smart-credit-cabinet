package ru.creditcabinet.agent.controller

import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.agent.application.AgentClientService
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.company.controller.CompanyResponse
import ru.creditcabinet.company.domain.LegalForm
import java.time.Instant
import java.util.UUID

data class AgentClientSummary(
    val companyId: UUID,
    val inn: String,
    val name: String?,
    val legalForm: LegalForm,
    val assignedAt: Instant,
)

data class AgentAccessResponse(
    val allowed: Boolean,
    val company: CompanyResponse?,
    val mandateId: UUID? = null,
)

@Tag(name = "Агентский канал")
@RestController
@RequestMapping("/api/v1/agent")
class AgentClientController(
    private val service: AgentClientService,
) {
    @Operation(summary = "Компании, закреплённые за текущим агентом")
    @GetMapping("/clients")
    fun clients(): List<AgentClientSummary> {
        val caller = CurrentCaller.requireRole(Roles.AGENT)
        return service.listClients(caller.userId).map { entry ->
            AgentClientSummary(
                companyId = entry.company.id,
                inn = entry.company.inn,
                name = entry.company.name,
                legalForm = entry.company.legalForm,
                assignedAt = entry.assignedAt,
            )
        }
    }
}

/** Agent mandate checks for other services (credit verifies active mandate before acting for a company). */
@Hidden
@RestController
@RequestMapping("/internal/v1/agents")
class InternalAgentClientController(
    private val service: AgentClientService,
) {
    @GetMapping("/{agentId}/companies")
    fun companies(@PathVariable agentId: UUID): List<UUID> = service.listCompanyIds(agentId)

    @GetMapping("/{agentId}/companies/{companyId}/access")
    fun access(@PathVariable agentId: UUID, @PathVariable companyId: UUID): AgentAccessResponse {
        val access = service.checkAccess(agentId, companyId)
        return if (access != null) {
            val company = access.company
            AgentAccessResponse(
                allowed = true,
                mandateId = access.mandate.id,
                company = CompanyResponse(
                    id = company.id,
                    inn = company.inn,
                    name = company.name,
                    legalForm = company.legalForm,
                    members = emptyList(),
                ),
            )
        } else {
            AgentAccessResponse(allowed = false, company = null)
        }
    }
}
