package ru.creditcabinet.agent.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.agent.application.AgentApplicationService
import ru.creditcabinet.agent.domain.ActionOwner
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.InfoRequests
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.document.application.ApplicationDocuments
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.financing.scenario.application.FinancingScenarios
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class AgentCaseItem(
    val applicationId: UUID,
    val number: Long?,
    val companyName: String?,
    val status: ApplicationStatus,
    val actionOwner: ActionOwner?,
    val actionLabel: String?,
    val actionDeadline: Instant?,
    val cabinetPath: String,
)

data class AgentMcpPriorities(val source: String, val asOf: Instant, val items: List<AgentCaseItem>)

enum class StatementRequirement { OPEN, SATISFIED, NOT_REQUESTED }

data class AgentMcpApplicationSummary(
    val source: String,
    val asOf: Instant,
    val application: AgentCaseItem,
    val applicationVersion: String,
    val savedScenarioRevision: String?,
    val statementRequirement: StatementRequirement,
)

@Tag(name = "Агентский канал: заявки, портфель, документы и чат")
@RestController
@RequestMapping("/api/v1/agent/mcp")
class AgentMcpController(
    private val agents: AgentApplicationService,
    private val infoRequests: InfoRequests,
    private val documents: ApplicationDocuments,
    private val scenarios: FinancingScenarios,
    private val clock: Clock,
) {
    @Operation(summary = "Ближайшие действия по доступным заявкам агента")
    @GetMapping("/priorities")
    fun mcpPriorities(
        @Parameter(schema = Schema(type = "integer", format = "int32", defaultValue = "10", minimum = "1", maximum = "20"))
        @RequestParam(defaultValue = "10") limit: Int,
    ): AgentMcpPriorities {
        require(limit in 1..20) { "Укажите лимит от 1 до 20 заявок" }
        val items = agents.listApplications().map(::item)
            .filter { it.actionOwner != null }
            .sortedWith(
                compareBy<AgentCaseItem> { it.actionDeadline == null }
                    .thenBy { it.actionDeadline }
                    .thenBy { it.actionOwner != ActionOwner.AGENT }
                    .thenBy { it.applicationId },
            )
            .take(limit)
        return AgentMcpPriorities("credit", clock.instant(), items)
    }

    @Operation(summary = "Краткая сводка доступной заявки агента")
    @GetMapping("/applications/{id}/summary")
    fun mcpApplicationSummary(@PathVariable id: UUID): AgentMcpApplicationSummary {
        val application = agents.get(id)
        val request = infoRequests.open(id)
        val statement = when {
            request == null || request.items.none { it.documentType == DocumentType.BANK_STATEMENT } -> StatementRequirement.NOT_REQUESTED
            DocumentType.BANK_STATEMENT in documents.satisfiedDocumentTypes(request) -> StatementRequirement.SATISFIED
            else -> StatementRequirement.OPEN
        }
        return AgentMcpApplicationSummary(
            "credit",
            clock.instant(),
            item(application),
            checkNotNull(application.version).toString(),
            scenarios.savedRevision(id)?.toString(),
            statement,
        )
    }

    private fun item(application: LoanApplication): AgentCaseItem {
        val action = agents.action(application)
        val anchor = when (application.status) {
            ApplicationStatus.DRAFT -> "#draft-financing"
            ApplicationStatus.INFO_REQUESTED -> "#application-info-request"
            else -> ""
        }
        return AgentCaseItem(
            application.id,
            application.number,
            application.company.name,
            application.status,
            action?.owner,
            action?.label,
            action?.deadline,
            "/agent/applications/${application.id}$anchor",
        )
    }
}
