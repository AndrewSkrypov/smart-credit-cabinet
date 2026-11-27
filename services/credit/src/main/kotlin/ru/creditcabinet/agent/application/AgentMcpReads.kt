package ru.creditcabinet.agent.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.agent.domain.ActionOwner
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.InfoRequests
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.document.domain.DocumentContext
import ru.creditcabinet.document.domain.DocumentRepository
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.financing.scenario.application.FinancingScenarios
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

@Service
@Transactional(readOnly = true)
internal class AgentMcpReads(
    private val agents: AgentApplicationService,
    private val scenarios: FinancingScenarios,
    private val requests: InfoRequests,
    private val documents: DocumentRepository,
    private val clock: Clock,
) {
    fun priorities(limit: Int): AgentMcpPriorities =
        AgentMcpPriorities(
            clock.instant(),
            agents.listApplications().asSequence()
                .map(::caseItem)
                .filter { it.actionOwner != null }
                .sortedWith(
                    compareBy<AgentMcpCase> { it.actionDeadline == null }
                        .thenBy { it.actionDeadline }
                        .thenBy { it.actionOwner != ActionOwner.AGENT },
                )
                .take(limit)
                .toList(),
        )

    fun summary(id: UUID): AgentMcpSummary {
        val application = agents.get(id)
        return AgentMcpSummary(
            clock.instant(),
            caseItem(application),
            application.version?.toString(),
            scenarios.forAgent(application)?.revision?.toString(),
            statementRequirement(application),
        )
    }

    fun preview(id: UUID, delayDays: Int, alternativeTermMonths: Int): AgentMcpPreview {
        val application = agents.get(id)
        val (product, calculation) = scenarios.previewSavedDraft(application, delayDays, alternativeTermMonths)
        val saved = calculation.inputs
        val lastIncluded = ChronoUnit.DAYS.between(saved.expectedReceiptDate, saved.paymentDate).takeIf { it >= 0 }
        return AgentMcpPreview(
            clock.instant(),
            AgentMcpBasis(checkNotNull(application.version).toString(), product.code, product.version.toString(), calculation.revision.toString()),
            AgentMcpInputs(
                paymentModel = if (product.revolving) "REVOLVING" else "ANNUITY",
                loanAmountRub = calculation.loanAmount.toString(),
                annualRatePercent = calculation.annualRatePercent.toPlainString(),
                baseTermMonths = application.terms.termMonths,
                alternativeTermMonths = saved.alternativeTermMonths,
                openingBalanceRub = saved.openingBalance.toString(),
                expectedReceiptAmountRub = saved.expectedReceiptAmount.toString(),
                expectedReceiptDate = saved.expectedReceiptDate,
                paymentDate = saved.paymentDate,
                delayDays = saved.delayDays,
                receiptIncludedByPayment = lastIncluded != null && saved.delayDays <= lastIncluded,
                lastIncludedDelayDays = lastIncluded,
            ),
            calculation.options.map {
                AgentMcpOption(it.termMonths, it.monthlyPayment.toPlainString(), it.totalInterest.toPlainString(), it.balanceOnPaymentDate.toPlainString())
            },
            "/agent/applications/$id#draft-financing",
        )
    }

    private fun caseItem(application: LoanApplication): AgentMcpCase {
        val action = agents.action(application)
        val suffix = if (application.status == ApplicationStatus.INFO_REQUESTED && action != null) "#application-info-request" else ""
        return AgentMcpCase(
            application.id,
            application.number,
            application.company.name,
            application.status,
            action?.owner,
            action?.label,
            action?.deadline,
            "/agent/applications/${application.id}$suffix",
        )
    }

    private fun statementRequirement(application: LoanApplication): StatementRequirement {
        val request = requests.open(application.id)
        if (request == null || request.items.none { it.documentType == DocumentType.BANK_STATEMENT }) {
            return StatementRequirement.NOT_REQUESTED
        }
        val satisfied = documents.list(application.id).any {
            it.type == DocumentType.BANK_STATEMENT && it.context == DocumentContext.BUSINESS && !it.uploadedAt.isBefore(request.createdAt)
        }
        return if (satisfied) StatementRequirement.SATISFIED else StatementRequirement.OPEN
    }
}

internal enum class StatementRequirement { OPEN, SATISFIED, NOT_REQUESTED }

internal data class AgentMcpCase(
    val applicationId: UUID,
    val number: Long?,
    val companyName: String?,
    val status: ApplicationStatus,
    val actionOwner: ActionOwner?,
    val actionLabel: String?,
    val actionDeadline: Instant?,
    val cabinetPath: String,
)

internal data class AgentMcpPriorities(
    val asOf: Instant,
    val items: List<AgentMcpCase>,
    val source: String = "credit",
)

internal data class AgentMcpSummary(
    val asOf: Instant,
    val application: AgentMcpCase,
    val applicationVersion: String?,
    val savedScenarioRevision: String?,
    val statementRequirement: StatementRequirement,
    val source: String = "credit",
)

internal data class AgentMcpBasis(
    val applicationVersion: String,
    val productCode: String,
    val productVersion: String,
    val savedScenarioRevision: String,
)

internal data class AgentMcpInputs(
    val paymentModel: String,
    val loanAmountRub: String,
    val annualRatePercent: String,
    val baseTermMonths: Int,
    val alternativeTermMonths: Int,
    val openingBalanceRub: String,
    val expectedReceiptAmountRub: String,
    val expectedReceiptDate: LocalDate,
    val paymentDate: LocalDate,
    val delayDays: Int,
    val receiptIncludedByPayment: Boolean,
    val lastIncludedDelayDays: Long?,
    val currency: String = "RUB",
)

internal data class AgentMcpOption(
    val termMonths: Int,
    val monthlyPaymentRub: String,
    val totalInterestRub: String,
    val balanceOnPaymentDateRub: String,
)

internal data class AgentMcpPreview(
    val asOf: Instant,
    val basis: AgentMcpBasis,
    val inputsUsed: AgentMcpInputs,
    val options: List<AgentMcpOption>,
    val cabinetPath: String,
    val preview: Boolean = true,
    val source: String = "credit",
)
