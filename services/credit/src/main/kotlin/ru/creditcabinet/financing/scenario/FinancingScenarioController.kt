package ru.creditcabinet.financing.scenario

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.agent.application.AgentApplicationService
import ru.creditcabinet.application.service.ApplicationService
import ru.creditcabinet.common.security.CurrentCaller
import java.util.UUID

@Tag(name = "Сценарий финансирования")
@RestController
class FinancingScenarioController(
    private val agents: AgentApplicationService,
    private val applications: ApplicationService,
    private val scenarios: FinancingScenarios,
) {
    @Operation(summary = "Сценарий агентской заявки")
    @GetMapping("/api/v1/agent/applications/{id}/financing-scenario")
    fun agentGet(@PathVariable id: UUID): ResponseEntity<FinancingScenarioSnapshot> =
        response(scenarios.forAgent(agents.get(id)))

    @Operation(summary = "Сравнить два срока и сохранить редакцию сценария")
    @PutMapping("/api/v1/agent/applications/{id}/financing-scenario")
    @Transactional
    fun agentPut(@PathVariable id: UUID, @RequestBody inputs: FinancingScenarioInputs): FinancingScenarioSnapshot {
        val (_, application) = agents.ownAgentApplication(id, forUpdate = true)
        return scenarios.saveDraft(application, inputs)
    }

    @Operation(summary = "Последний переданный клиенту пакет")
    @GetMapping("/api/v1/applications/{id}/financing-scenario")
    fun clientGet(@PathVariable id: UUID): ResponseEntity<FinancingScenarioSnapshot> =
        response(scenarios.handed(applications.own(id).second.id))

    @Operation(summary = "Пакет поданной в банк заявки")
    @GetMapping("/api/v1/underwriting/applications/{id}/financing-scenario")
    fun bankGet(@PathVariable id: UUID): ResponseEntity<FinancingScenarioSnapshot> {
        CurrentCaller.requireBankStaff()
        return response(scenarios.handed(applications.get(id).id))
    }

    private fun response(snapshot: FinancingScenarioSnapshot?): ResponseEntity<FinancingScenarioSnapshot> =
        snapshot?.let { ResponseEntity.ok(it) } ?: ResponseEntity.noContent().build()
}
