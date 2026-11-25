package ru.creditcabinet.underwriting.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.InfoItem
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.assessment.domain.BankInfo
import ru.creditcabinet.assessment.domain.BureauInfo
import ru.creditcabinet.assessment.domain.Decision
import ru.creditcabinet.assessment.domain.DecisionOutcome
import ru.creditcabinet.assessment.domain.FactorScore
import ru.creditcabinet.assessment.domain.Rating
import ru.creditcabinet.assessment.domain.Recommendation
import ru.creditcabinet.assessment.domain.RegistryInfo
import ru.creditcabinet.assessment.domain.SourceKind
import ru.creditcabinet.assessment.domain.TaxInfo
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.underwriting.application.UnderwritingAssessment
import ru.creditcabinet.underwriting.application.UnderwritingDecisions
import ru.creditcabinet.underwriting.domain.Discrepancy
import java.time.Instant
import java.util.UUID

data class ReasonView(val code: String, val title: String)

data class DecisionView(
    val id: UUID,
    val decidedByType: String,
    val decidedBy: UUID?,
    val ruleSetVersion: Int,
    val score: Int?,
    val rating: Rating?,
    val stopFactors: List<ReasonView>,
    val factors: List<FactorScore>,
    val outcome: DecisionOutcome,
    val reasonCode: String?,
    val manualReasons: List<ReasonView>,
    val recommendation: Recommendation?,
    val comment: String?,
    val createdAt: Instant,
) {
    companion object {
        fun of(decision: Decision) =
            DecisionView(
                id = decision.id,
                decidedByType = decision.decidedByType.name,
                decidedBy = decision.decidedBy,
                ruleSetVersion = decision.ruleSetVersion,
                score = decision.score,
                rating = decision.rating,
                stopFactors = decision.stopFactors.map { ReasonView(it.name, it.clientText) },
                factors = decision.factors,
                outcome = decision.outcome,
                reasonCode = decision.reasonCode,
                manualReasons = decision.manualReasons.map { ReasonView(it.name, it.title) },
                recommendation = decision.recommendation,
                comment = decision.comment,
                createdAt = decision.createdAt,
            )
    }
}

/** The underwriter's card: the sources, where they disagree with the client, the score and what the rules propose. */
data class AssessmentView(
    val applicationId: UUID,
    val status: ApplicationStatus,
    val registry: RegistryInfo?,
    val tax: TaxInfo?,
    val bureau: BureauInfo?,
    val bank: BankInfo?,
    /** When responses reached the cabinet; does not assert when the source's facts became current. */
    val sourceFetchedAt: Map<SourceKind, Instant>,
    val turnoverVariation: Double?,
    val discrepancies: List<Discrepancy>,
    val automaticDecision: DecisionView?,
    val latestDecision: DecisionView?,
    /** Actions open to the caller now: APPROVE, COUNTER_OFFER, REQUEST_INFO, REJECT. */
    val actions: List<String>,
)

/** The application after an underwriter's action. */
data class ActionResult(val applicationId: UUID, val status: ApplicationStatus)

private fun LoanApplication.result() = ActionResult(id, status)

data class CommentRequest(@field:Size(max = 2000) val comment: String? = null)

data class CounterOfferRequest(
    val productCode: String? = null,
    @field:Positive(message = "Сумма должна быть больше нуля")
    val amount: Long,
    @field:Min(1)
    @field:Max(120)
    val termMonths: Int,
    @field:Size(max = 2000) val comment: String? = null,
)

data class RequestedItem(val documentType: DocumentType? = null, @field:Size(max = 500) val question: String? = null)

data class InfoRequestBody(
    @field:Size(min = 1, max = 10, message = "От 1 до 10 пунктов")
    val items: List<RequestedItem>,
    @field:Size(max = 2000) val comment: String? = null,
)

@Tag(name = "Решения сотрудников банка")
@RestController
@RequestMapping("/api/v1/underwriting/applications")
class UnderwritingController(
    private val service: UnderwritingDecisions,
    private val assessments: UnderwritingAssessment,
) {
    @Operation(summary = "Карточка оценки: данные источников, расхождения, скоринг и рекомендация")
    @GetMapping("/{id}")
    fun assessment(@PathVariable id: UUID): AssessmentView {
        val card = assessments.read(id)
        return AssessmentView(
            applicationId = card.applicationId,
            status = card.status,
            registry = card.registry,
            tax = card.tax,
            bureau = card.bureau,
            bank = card.bank,
            sourceFetchedAt = card.sourceFetchedAt,
            turnoverVariation = card.turnoverVariation,
            discrepancies = card.discrepancies,
            automaticDecision = card.automaticDecision?.let(DecisionView::of),
            latestDecision = card.latestDecision?.let(DecisionView::of),
            actions = card.actions,
        )
    }

    @Operation(summary = "Одобрить на запрошенных условиях (T13)")
    @PostMapping("/{id}/approve")
    fun approve(@PathVariable id: UUID, @Valid @RequestBody(required = false) request: CommentRequest?) = service.approve(id, request?.comment).result()

    @Operation(summary = "Предложить другие условия (T13)")
    @PostMapping("/{id}/counter-offer")
    fun counterOffer(@PathVariable id: UUID, @Valid @RequestBody request: CounterOfferRequest) =
        service.counterOffer(id, request.productCode, request.amount, request.termMonths, request.comment).result()

    @Operation(summary = "Запросить документы или пояснения у клиента (T14)")
    @PostMapping("/{id}/request-info")
    fun requestInfo(@PathVariable id: UUID, @Valid @RequestBody request: InfoRequestBody) =
        service.requestInfo(
            id,
            request.items.map {
                InfoItem(documentType = it.documentType, question = it.question?.trim()?.takeIf(String::isNotEmpty))
            },
            request.comment,
        ).status

    @Operation(summary = "Отказать с объяснением для клиента (T15)")
    @PostMapping("/{id}/reject")
    fun reject(@PathVariable id: UUID, @Valid @RequestBody request: CommentRequest) = service.reject(id, request.comment).result()
}
