package ru.creditcabinet.underwriting

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.application.domain.ApplicationEvent
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.InfoItem
import ru.creditcabinet.application.domain.InfoRequest
import ru.creditcabinet.application.domain.InfoRequests
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.domain.PipelineJobs
import ru.creditcabinet.application.domain.PipelineStep
import ru.creditcabinet.application.service.Transitions
import ru.creditcabinet.application.service.notFound
import ru.creditcabinet.application.service.toActor
import ru.creditcabinet.assessment.domain.CreditPolicy
import ru.creditcabinet.assessment.domain.Decision
import ru.creditcabinet.assessment.domain.DecisionOutcome
import ru.creditcabinet.assessment.domain.DecisionRepository
import ru.creditcabinet.assessment.domain.OfferTerms
import ru.creditcabinet.assessment.domain.RejectReason
import ru.creditcabinet.assessment.domain.RuleSets
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.offer.OfferIssuer
import ru.creditcabinet.offer.OfferKind
import ru.creditcabinet.pipeline.PipelineSettings
import ru.creditcabinet.product.domain.ProductCatalog
import ru.creditcabinet.product.domain.ProductMatcher.rubles
import java.time.Clock
import java.util.UUID

/**
 * Decisions of an underwriter on applications in manual review (T13–T15). The rules'
 * recommendation is shown next to every action; going against it needs a comment, which goes to
 * the journal and the decision record.
 */
@Service
class UnderwritingService(
    private val applications: ApplicationRepository,
    private val transitions: Transitions,
    private val decisions: DecisionRepository,
    private val issuer: OfferIssuer,
    private val infoRequests: InfoRequests,
    private val jobs: PipelineJobs,
    private val products: ProductCatalog,
    private val ruleSets: RuleSets,
    private val settings: PipelineSettings,
    private val clock: Clock,
) {
    @Transactional
    fun approve(id: UUID, comment: String?): LoanApplication {
        val (claims, application, automatic) = reviewed(id, Roles.UNDERWRITER)
        requireJustified(automatic, DecisionOutcome.APPROVE, comment)
        val product = products.find(application.terms.productCode)!!
        val terms = OfferTerms.of(
            product,
            application.terms.amount,
            application.terms.termMonths,
            CreditPolicy.rate(product, automatic?.rating, ruleSets.current()),
        )
        val decision = record(application, claims, automatic, DecisionOutcome.APPROVE, null, comment)
        return issuer.issue(
            application, decision, OfferKind.APPROVED, terms, ApplicationEvent.APPROVE, claims.toActor()!!, clock.instant(),
            mapOf(
                "decisionId" to decision.id.toString(),
            ),
            comment,
        )
    }

    @Transactional
    fun counterOffer(id: UUID, productCode: String?, amount: Long, termMonths: Int, comment: String?): LoanApplication {
        val (claims, application, automatic) = reviewed(id, Roles.UNDERWRITER)
        val product = products.find(productCode ?: application.terms.productCode) ?: throw NotFoundException("Продукт не найден")
        require(product.amountFits(amount)) { "Сумма для «${product.name}» — от ${rubles(product.minAmount)} до ${rubles(product.maxAmount)}" }
        require(product.termFits(termMonths)) { "Срок для «${product.name}» — от ${product.minTermMonths} до ${product.maxTermMonths} мес." }
        val sameAsRecommended =
            automatic?.recommendation?.terms?.let { it.productCode == product.code && it.amount == amount && it.termMonths == termMonths } == true
        if (!sameAsRecommended) requireComment(comment, "Укажите, почему условия отличаются от рекомендованных")
        val terms = OfferTerms.of(product, amount, termMonths, CreditPolicy.rate(product, automatic?.rating, ruleSets.current()))
        val decision = record(application, claims, automatic, DecisionOutcome.COUNTER_OFFER, null, comment)
        return issuer.issue(
            application, decision, OfferKind.COUNTER, terms, ApplicationEvent.COUNTER_OFFER, claims.toActor()!!, clock.instant(),
            mapOf(
                "decisionId" to decision.id.toString(),
            ),
            comment,
        )
    }

    /** T14: a manager asks for documents too; the client gets a list and a deadline. */
    @Transactional
    fun requestInfo(id: UUID, items: List<InfoItem>, comment: String?): LoanApplication {
        val (claims, application, automatic) = reviewed(id, Roles.UNDERWRITER, Roles.MANAGER)
        require(items.isNotEmpty()) { "Укажите, что нужно от клиента" }
        val now = clock.instant()
        val request = InfoRequest(
            applicationId = application.id,
            items = items,
            comment = comment?.take(MAX_COMMENT),
            deadline = now.plus(settings.infoRequestTtl),
            requestedBy = claims.toActor()!!,
            createdAt = now,
        )
        infoRequests.save(request)
        record(application, claims, automatic, DecisionOutcome.REQUEST_INFO, null, comment)
        val saved = transitions.commit(
            application.move(
                ApplicationEvent.REQUEST_INFO,
                claims.toActor()!!,
                now,
                comment = comment?.take(MAX_COMMENT),
                payload = mapOf("items" to items, "deadline" to request.deadline.toString()),
            ),
        )
        jobs.enqueue(application.id, PipelineStep.EXPIRE_INFO_REQUEST, request.deadline)
        return saved
    }

    /** T15: a refusal always explains itself to the client. */
    @Transactional
    fun reject(id: UUID, comment: String?): LoanApplication {
        val (claims, application, automatic) = reviewed(id, Roles.UNDERWRITER)
        requireComment(comment, "Укажите причину отказа — клиент увидит её")
        record(application, claims, automatic, DecisionOutcome.REJECT, RejectReason.R_MANUAL.name, comment)
        return transitions.commit(
            application.move(
                ApplicationEvent.REJECT,
                claims.toActor()!!,
                clock.instant(),
                reasonCode = RejectReason.R_MANUAL.name,
                comment = comment!!.take(MAX_COMMENT),
            ),
        )
    }

    private data class Reviewed(val claims: TokenClaims, val application: LoanApplication, val automatic: Decision?)

    private fun reviewed(id: UUID, vararg roles: String): Reviewed {
        val claims = CurrentCaller.requireRole(*roles)
        val application = applications.find(id)?.takeIf { it.visibleToBank } ?: throw notFound()
        if (application.status != ApplicationStatus.MANUAL_REVIEW) throw ConflictException("Заявка не на ручном рассмотрении")
        return Reviewed(claims, application, decisions.latestAutomatic(application.id))
    }

    /** An action against the rules' recommendation, or without one (a source was silent), needs a reason. */
    private fun requireJustified(automatic: Decision?, outcome: DecisionOutcome, comment: String?) {
        if (automatic?.recommendation?.outcome != outcome) requireComment(comment, "Решение расходится с рекомендацией системы — укажите причину")
    }

    private fun requireComment(comment: String?, message: String) = require(!comment.isNullOrBlank()) { message }

    private fun record(
        application: LoanApplication,
        claims: TokenClaims,
        automatic: Decision?,
        outcome: DecisionOutcome,
        reason: String?,
        comment: String?,
    ): Decision {
        val decision = Decision(
            applicationId = application.id,
            ruleSetVersion = automatic?.ruleSetVersion ?: ruleSets.current().version,
            decidedByType = claims.toActor()!!.type,
            decidedBy = claims.userId,
            score = automatic?.score,
            rating = automatic?.rating,
            stopFactors = automatic?.stopFactors.orEmpty(),
            factors = automatic?.factors.orEmpty(),
            outcome = outcome,
            reasonCode = reason,
            manualReasons = automatic?.manualReasons.orEmpty(),
            recommendation = automatic?.recommendation,
            inputs = mapOf("automaticDecisionId" to automatic?.id?.toString()),
            comment = comment?.take(MAX_COMMENT),
            createdAt = clock.instant(),
        )
        decisions.save(decision)
        return decision
    }

    private companion object {
        const val MAX_COMMENT = 2000
    }
}
