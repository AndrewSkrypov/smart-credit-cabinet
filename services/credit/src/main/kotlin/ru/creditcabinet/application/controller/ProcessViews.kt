package ru.creditcabinet.application.controller

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import ru.creditcabinet.application.domain.InfoItem
import ru.creditcabinet.application.domain.InfoRequest
import ru.creditcabinet.assessment.domain.Decision
import ru.creditcabinet.assessment.domain.DecisionOutcome
import ru.creditcabinet.assessment.domain.RejectReason
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.offer.Loan
import ru.creditcabinet.offer.Offer
import ru.creditcabinet.offer.OfferKind
import ru.creditcabinet.offer.OfferStatus
import ru.creditcabinet.offer.SigningSessionRecord
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class InfoItemView(val documentType: DocumentType?, val title: String, val reason: String?, val question: String?) {
    companion object {
        fun of(item: InfoItem) = InfoItemView(item.documentType, item.documentType?.title ?: item.question!!, item.reason, item.question)
    }
}

data class InfoRequestView(val id: UUID, val items: List<InfoItemView>, val comment: String?, val deadline: Instant, val requestedAt: Instant) {
    companion object {
        fun of(request: InfoRequest) = InfoRequestView(request.id, request.items.map(InfoItemView::of), request.comment, request.deadline, request.createdAt)
    }
}

data class OfferView(
    val id: UUID,
    val kind: OfferKind,
    val status: OfferStatus,
    val productCode: String,
    val productName: String,
    val amount: Long,
    val termMonths: Int,
    val rate: BigDecimal,
    val monthlyPayment: BigDecimal,
    val totalPayment: BigDecimal,
    val validUntil: Instant,
    /** What changed against the request, in words: shown next to a counter offer. */
    val changes: List<String>,
) {
    companion object {
        fun of(offer: Offer, productName: String, changes: List<String>) =
            OfferView(
                offer.id,
                offer.kind,
                offer.status,
                offer.productCode,
                productName,
                offer.amount,
                offer.termMonths,
                offer.rate,
                offer.monthlyPayment,
                offer.totalPayment,
                offer.validUntil,
                changes,
            )
    }
}

/** The one-time code is shown in the cabinet: SMS is outside the scenario (docs/integrations.md). */
data class SigningView(val code: String?, val expiresAt: Instant?, val signedAt: Instant?)

data class LoanView(
    val contractNumber: String,
    val loanAccount: String,
    val settlementAccount: String,
    val amount: Long,
    val termMonths: Int,
    val rate: BigDecimal,
    val disbursedAt: Instant,
    val maturityDate: LocalDate,
) {
    companion object {
        fun of(
            loan: Loan,
        ) = LoanView(
            loan.contractNumber,
            loan.loanAccount,
            loan.settlementAccount,
            loan.amount,
            loan.termMonths,
            loan.rate,
            loan.disbursedAt,
            loan.maturityDate,
        )
    }
}

/** A refusal as the client sees it: the reason and what can be done. */
data class OutcomeView(val outcome: DecisionOutcome, val reasonCode: String?, val reasonTitle: String?, val explanation: List<String>) {
    companion object {
        fun of(decision: Decision): OutcomeView {
            val reason = decision.reasonCode?.let { code -> RejectReason.entries.firstOrNull { it.name == code } }
            val explanation = when {
                decision.comment != null -> listOf(decision.comment)
                else -> decision.recommendation?.notes.orEmpty()
            }
            return OutcomeView(decision.outcome, decision.reasonCode, reason?.clientText, explanation)
        }
    }
}

/** What the process adds to an application once it is sent. */
data class ProcessView(
    val infoRequest: InfoRequestView?,
    val offer: OfferView?,
    val signing: SigningView?,
    val loan: LoanView?,
    val outcome: OutcomeView?,
)

data class ProvideInfoRequest(@field:Size(max = 2000) val answer: String? = null)

data class DeclineRequest(@field:Size(max = 2000) val comment: String? = null)

data class SignRequest(@field:NotBlank(message = "Введите код") val code: String)

fun SigningSessionRecord.toView(showCode: Boolean) = SigningView(code.takeIf { showCode && signedAt == null }, expiresAt, signedAt)
