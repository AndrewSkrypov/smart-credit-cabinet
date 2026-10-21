package ru.creditcabinet.offer

import org.springframework.stereotype.Component
import ru.creditcabinet.application.domain.Actor
import ru.creditcabinet.application.domain.ApplicationEvent
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.domain.PipelineJobs
import ru.creditcabinet.application.domain.PipelineStep
import ru.creditcabinet.application.service.Transitions
import ru.creditcabinet.assessment.domain.Decision
import ru.creditcabinet.assessment.domain.OfferTerms
import ru.creditcabinet.pipeline.PipelineSettings
import java.time.Instant

/** Issues an offer: the offer itself, the transition to OFFER_ISSUED and the job that expires it. Called inside a transaction. */
@Component
class OfferIssuer(
    private val offers: OfferRepository,
    private val transitions: Transitions,
    private val jobs: PipelineJobs,
    private val settings: PipelineSettings,
) {
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
    ): LoanApplication {
        val offer = Offer(
            applicationId = application.id,
            decisionId = decision.id,
            kind = kind,
            productCode = terms.productCode,
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
            application.move(event, actor, now, reasonCode = kind.name, comment = comment, payload = payload + ("offerId" to offer.id.toString())),
        )
        jobs.enqueue(application.id, PipelineStep.EXPIRE_OFFER, offer.validUntil)
        return saved
    }
}
