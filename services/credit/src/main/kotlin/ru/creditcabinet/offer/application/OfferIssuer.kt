package ru.creditcabinet.offer.application

import org.springframework.stereotype.Component
import ru.creditcabinet.application.application.CommitTransition
import ru.creditcabinet.application.domain.Actor
import ru.creditcabinet.application.domain.ApplicationEvent
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.domain.PipelineJobs
import ru.creditcabinet.application.domain.PipelineStep
import ru.creditcabinet.assessment.domain.Decision
import ru.creditcabinet.assessment.domain.OfferTerms
import ru.creditcabinet.offer.domain.Offer
import ru.creditcabinet.offer.domain.OfferKind
import ru.creditcabinet.offer.domain.OfferRepository
import ru.creditcabinet.pipeline.PipelineSettings
import java.time.Instant

interface OfferIssuance {
    /** The caller commits the decision, offer, application transition and expiry job together. */
    fun issue(
        application: LoanApplication,
        decision: Decision,
        kind: OfferKind,
        terms: OfferTerms,
        event: ApplicationEvent,
        actor: Actor,
        now: Instant,
        payload: Map<String, Any?>,
        comment: String? = null,
    ): LoanApplication
}

@Component
class OfferIssuer(
    private val offers: OfferRepository,
    private val transitions: CommitTransition,
    private val jobs: PipelineJobs,
    private val settings: PipelineSettings,
) : OfferIssuance {
    override fun issue(
        application: LoanApplication,
        decision: Decision,
        kind: OfferKind,
        terms: OfferTerms,
        event: ApplicationEvent,
        actor: Actor,
        now: Instant,
        payload: Map<String, Any?>,
        comment: String?,
    ): LoanApplication {
        val offer = Offer(
            applicationId = application.id,
            decisionId = decision.id,
            kind = kind,
            productCode = terms.productCode,
            productVersion = application.productCatalogSnapshot[terms.productCode]
                ?: throw IllegalStateException("Предложение требует зафиксированную версию продукта"),
            amount = terms.amount,
            termMonths = terms.termMonths,
            rate = terms.rate,
            monthlyPayment = terms.monthlyPayment,
            totalPayment = terms.totalPayment,
            validUntil = now.plus(settings.offerTtl),
            createdAt = now,
        )
        offers.save(offer)
        val saved = transitions.commit(
            application.move(
                event,
                actor,
                now,
                reasonCode = kind.name,
                comment = comment,
                payload = payload + mapOf("offerId" to offer.id.toString(), "deadline" to offer.validUntil.toString()),
            ),
        )
        jobs.enqueue(application.id, PipelineStep.EXPIRE_OFFER, offer.validUntil)
        return saved
    }
}
