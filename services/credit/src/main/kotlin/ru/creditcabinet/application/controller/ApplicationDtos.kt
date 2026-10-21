package ru.creditcabinet.application.controller

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import ru.creditcabinet.application.domain.ActorType
import ru.creditcabinet.application.domain.ApplicationEvent
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.CloseReason
import ru.creditcabinet.application.domain.JournalEntry
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.document.domain.ApplicationDocument
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.product.domain.FinancingPurpose
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class ApplicationRequest(
    @field:NotBlank(message = "Выберите продукт")
    val productCode: String,
    val purpose: FinancingPurpose,
    @field:Size(max = 1000, message = "Комментарий к цели — до 1000 символов")
    val purposeComment: String? = null,
    @field:Positive(message = "Сумма должна быть больше нуля")
    val amount: Long,
    @field:Min(1, message = "Срок — от 1 месяца")
    @field:Max(120, message = "Срок — не больше 120 месяцев")
    val termMonths: Int,
    @field:Positive(message = "Выручка должна быть больше нуля")
    val declaredRevenue12m: Long? = null,
    @field:PositiveOrZero(message = "Платежи по кредитам не могут быть отрицательными")
    val declaredMonthlyDebtPayments: Long? = null,
    val bureauConsent: Boolean = false,
) {
    fun toTerms() =
        ApplicationTerms(
            productCode = productCode.trim(),
            purpose = purpose,
            purposeComment = purposeComment?.trim()?.takeIf { it.isNotEmpty() },
            amount = amount,
            termMonths = termMonths,
            declaredRevenue12m = declaredRevenue12m,
            declaredMonthlyDebtPayments = declaredMonthlyDebtPayments,
            bureauConsent = bureauConsent,
        )
}

data class WithdrawRequest(
    @field:Size(max = 2000)
    val comment: String? = null,
)

data class CompanyView(val id: UUID, val inn: String, val name: String?, val legalForm: LegalForm)

data class ProductView(val code: String, val name: String, val revolving: Boolean)

data class RequiredDocumentView(val type: DocumentType, val title: String, val reason: String, val uploaded: Boolean)

data class DocumentView(val id: UUID, val type: DocumentType, val title: String, val fileName: String, val sizeBytes: Long, val uploadedAt: Instant) {
    companion object {
        fun of(document: ApplicationDocument) =
            DocumentView(document.id, document.type, document.type.title, document.fileName, document.sizeBytes, document.uploadedAt)
    }
}

/** Payment at the product's base rate: what the client can expect before the bank's decision. */
data class EstimateView(val rate: BigDecimal, val monthlyPayment: BigDecimal)

data class ApplicationResponse(
    val id: UUID,
    val number: Long?,
    val status: ApplicationStatus,
    val closeReason: CloseReason?,
    val company: CompanyView,
    val product: ProductView,
    val purpose: FinancingPurpose,
    val purposeTitle: String,
    val purposeComment: String?,
    val amount: Long,
    val termMonths: Int,
    val declaredRevenue12m: Long?,
    val declaredMonthlyDebtPayments: Long?,
    val bureauConsent: Boolean,
    val createdAt: Instant,
    val submittedAt: Instant?,
    val statusChangedAt: Instant,
    val version: Long?,
    /** Events the caller can cause now: the interface shows these and only these as actions. */
    val allowedActions: List<ApplicationEvent>,
    val editable: Boolean,
    val canUploadDocuments: Boolean,
    /** What is still missing before the application can be sent; empty when it can. */
    val submissionProblems: List<String>,
    val requiredDocuments: List<RequiredDocumentView>,
    val documents: List<DocumentView>,
    val estimate: EstimateView?,
    val process: ProcessView,
)

data class ApplicationSummary(
    val id: UUID,
    val number: Long?,
    val status: ApplicationStatus,
    val companyInn: String,
    val companyName: String?,
    val productCode: String,
    val productName: String,
    val amount: Long,
    val termMonths: Int,
    val createdAt: Instant,
    val submittedAt: Instant?,
    val statusChangedAt: Instant,
)

data class JournalEntryView(
    val id: UUID,
    val event: String,
    val fromStatus: ApplicationStatus?,
    val toStatus: ApplicationStatus,
    val actorType: ActorType,
    /** Who in the bank acted: shown to staff only. */
    val actorId: UUID?,
    val reasonCode: String?,
    val comment: String?,
    val payload: Map<String, Any?>,
    val occurredAt: Instant,
) {
    companion object {
        fun of(entry: JournalEntry, forStaff: Boolean) =
            JournalEntryView(
                id = entry.id,
                event = entry.event,
                fromStatus = entry.fromStatus,
                toStatus = entry.toStatus,
                actorType = entry.actor.type,
                actorId = entry.actor.id.takeIf { forStaff || entry.actor.type == ActorType.CLIENT },
                reasonCode = entry.reasonCode,
                comment = entry.comment,
                payload = entry.payload,
                occurredAt = entry.occurredAt,
            )
    }
}
