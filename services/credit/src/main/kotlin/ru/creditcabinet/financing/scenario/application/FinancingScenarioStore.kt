package ru.creditcabinet.financing.scenario.application

import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.financing.scenario.domain.FinancingScenarioStatus
import ru.creditcabinet.financing.scenario.domain.ScenarioSnapshot
import java.util.UUID

data class ScenarioRevision(
    val productVersion: Long,
    val snapshot: ScenarioSnapshot,
    val documentIds: Set<UUID> = emptySet(),
    val handedTerms: ApplicationTerms? = null,
)

interface FinancingScenarioStore {
    fun latest(applicationId: UUID, handedOnly: Boolean = false): ScenarioRevision? =
        recent(applicationId, handedOnly, 1).singleOrNull()

    fun recent(applicationId: UUID, handedOnly: Boolean, limit: Int): List<ScenarioRevision>

    fun append(applicationId: UUID, revision: ScenarioRevision)

    fun publish(
        applicationId: UUID,
        revision: Long,
        status: FinancingScenarioStatus,
        documents: List<UUID> = emptyList(),
        handedTerms: ApplicationTerms? = null,
    )
}
