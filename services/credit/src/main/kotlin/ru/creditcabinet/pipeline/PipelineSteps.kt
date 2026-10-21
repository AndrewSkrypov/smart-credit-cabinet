package ru.creditcabinet.pipeline

import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.application.domain.Actor
import ru.creditcabinet.application.domain.ActorType
import ru.creditcabinet.application.domain.ApplicationEvent
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.InfoItem
import ru.creditcabinet.application.domain.InfoRequest
import ru.creditcabinet.application.domain.InfoRequests
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.domain.PipelineJob
import ru.creditcabinet.application.domain.PipelineJobs
import ru.creditcabinet.application.domain.PipelineStep
import ru.creditcabinet.application.service.Transitions
import ru.creditcabinet.assessment.domain.BankInfo
import ru.creditcabinet.assessment.domain.BureauInfo
import ru.creditcabinet.assessment.domain.CreditPolicy
import ru.creditcabinet.assessment.domain.Decision
import ru.creditcabinet.assessment.domain.DecisionOutcome
import ru.creditcabinet.assessment.domain.DecisionRepository
import ru.creditcabinet.assessment.domain.EnrichmentStore
import ru.creditcabinet.assessment.domain.ManualReason
import ru.creditcabinet.assessment.domain.PolicyDecision
import ru.creditcabinet.assessment.domain.Recommendation
import ru.creditcabinet.assessment.domain.RegistryInfo
import ru.creditcabinet.assessment.domain.RuleSets
import ru.creditcabinet.assessment.domain.Scorecard
import ru.creditcabinet.assessment.domain.SourceData
import ru.creditcabinet.assessment.domain.SourceKind
import ru.creditcabinet.assessment.domain.TaxInfo
import ru.creditcabinet.document.domain.DocumentRepository
import ru.creditcabinet.document.domain.DocumentRequirements
import ru.creditcabinet.integration.CompanySources
import ru.creditcabinet.integration.CoreBanking
import ru.creditcabinet.integration.LoanRequest
import ru.creditcabinet.integration.SourceUnavailableException
import ru.creditcabinet.offer.Loan
import ru.creditcabinet.offer.Loans
import ru.creditcabinet.offer.OfferKind
import ru.creditcabinet.offer.OfferRepository
import ru.creditcabinet.offer.OfferStatus
import ru.creditcabinet.offer.SigningSessions
import ru.creditcabinet.product.domain.ProductCatalog
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The automatic steps of the credit pipeline (docs/state-machine.md). Each step first checks that
 * the application is still where the step expects it (a client may have withdrawn it meanwhile),
 * calls external systems outside any transaction, then saves the transition, its journal entry,
 * the next step and the completion of its own job in one transaction.
 */
@Component
class PipelineSteps(
    private val applications: ApplicationRepository,
    private val transitions: Transitions,
    private val jobs: PipelineJobs,
    private val sources: CompanySources,
    private val core: CoreBanking,
    private val enrichment: EnrichmentStore,
    private val decisions: DecisionRepository,
    private val offers: OfferRepository,
    private val signing: SigningSessions,
    private val issuer: ru.creditcabinet.offer.OfferIssuer,
    private val loans: Loans,
    private val infoRequests: InfoRequests,
    private val documents: DocumentRepository,
    private val products: ProductCatalog,
    private val ruleSets: RuleSets,
    private val settings: PipelineSettings,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) {
    fun run(job: PipelineJob) {
        val application = applications.find(job.applicationId)
        if (application == null || application.status != expectedStatus(job.step)) {
            // Nothing to do any more: the application moved on or was withdrawn.
            jobs.complete(job.id)
            return
        }
        when (job.step) {
            PipelineStep.DATA_CHECK -> dataCheck(job, application)
            PipelineStep.ENRICHMENT -> enrich(job, application)
            PipelineStep.SCORING -> score(job, application)
            PipelineStep.DISBURSEMENT -> disburse(job, application)
            PipelineStep.EXPIRE_INFO_REQUEST -> expireInfoRequest(job, application)
            PipelineStep.EXPIRE_OFFER -> expireOffer(job, application)
        }
    }

    private fun expectedStatus(step: PipelineStep) =
        when (step) {
            PipelineStep.DATA_CHECK -> ApplicationStatus.SUBMITTED
            PipelineStep.ENRICHMENT -> ApplicationStatus.ENRICHMENT
            PipelineStep.SCORING -> ApplicationStatus.SCORING
            PipelineStep.DISBURSEMENT -> ApplicationStatus.DISBURSING
            PipelineStep.EXPIRE_INFO_REQUEST -> ApplicationStatus.INFO_REQUESTED
            PipelineStep.EXPIRE_OFFER -> ApplicationStatus.OFFER_ISSUED
        }

    /**
     * T3/T4. Whether a bank statement is needed depends on whether the company has an account
     * here, so the bank's own data is asked first; it is kept for the scoring as well.
     */
    private fun dataCheck(job: PipelineJob, application: LoanApplication) {
        val bank = try {
            enrichment.load(application.id)[SourceKind.BANK] as BankInfo? ?: sources.bank(application.company.inn)
        } catch (exception: SourceUnavailableException) {
            jobs.retry(job.id, clock.instant().plus(settings.sourceRetryDelay(job.attempts)), exception.message)
            return
        }
        val product = products.find(application.terms.productCode)!!
        val now = clock.instant()
        val uploaded = documents.list(application.id).map { it.type }.toSet()
        val missing = DocumentRequirements.required(product, application.terms.amount, application.company.legalForm, bank.client)
            .filter { it.type !in uploaded }
        tx.executeWithoutResult {
            enrichment.save(application.id, SourceKind.BANK, bank, now)
            if (missing.isEmpty()) {
                transitions.commit(application.move(ApplicationEvent.DATA_COMPLETE, Actor.SYSTEM, now))
                jobs.enqueue(application.id, PipelineStep.ENRICHMENT, now)
            } else {
                val request = InfoRequest(
                    applicationId = application.id,
                    items = missing.map { InfoItem(documentType = it.type, reason = it.reason) },
                    comment = "Для рассмотрения не хватает документов",
                    deadline = now.plus(settings.infoRequestTtl),
                    requestedBy = Actor.SYSTEM,
                    createdAt = now,
                )
                infoRequests.save(request)
                transitions.commit(
                    application.move(
                        ApplicationEvent.DATA_INCOMPLETE,
                        Actor.SYSTEM,
                        now,
                        reasonCode = "MISSING_DOCUMENTS",
                        payload = mapOf("documents" to missing.map { it.type.name }, "deadline" to request.deadline.toString()),
                    ),
                )
                jobs.enqueue(application.id, PipelineStep.EXPIRE_INFO_REQUEST, request.deadline)
            }
            jobs.complete(job.id)
        }
    }

    /** T7/T8/T9: asks every source not answered yet; a silent source is retried until the timeout. */
    private fun enrich(job: PipelineJob, application: LoanApplication) {
        val known = enrichment.load(application.id)
        val fetched = mutableMapOf<SourceKind, Any>()
        val failures = mutableMapOf<SourceKind, String>()
        for (kind in SourceKind.entries.filter { it !in known }) {
            try {
                // The application id identifies the client's consent to the bureau request.
                fetched[kind] = sources.fetch(kind, application.company.inn, application.id.toString())
            } catch (exception: SourceUnavailableException) {
                failures[kind] = exception.message ?: kind.title
            }
        }
        val now = clock.instant()
        tx.executeWithoutResult {
            fetched.forEach { (kind, data) -> enrichment.save(application.id, kind, data, now) }
            val waitedSince = application.statusChangedAt
            when {
                failures.isEmpty() -> {
                    transitions.commit(
                        application.move(
                            ApplicationEvent.ENRICHMENT_DONE,
                            Actor.SYSTEM,
                            now,
                            payload = mapOf(
                                "sources" to SourceKind.entries.map { it.name },
                            ),
                        ),
                    )
                    jobs.enqueue(application.id, PipelineStep.SCORING, now)
                    jobs.complete(job.id)
                }
                Duration.between(waitedSince, now) >= settings.sourceTimeout -> {
                    val decision = manualWithoutScore(application, failures.keys, now)
                    decisions.save(decision)
                    transitions.commit(
                        application.move(
                            ApplicationEvent.SOURCE_TIMED_OUT,
                            Actor.SYSTEM,
                            now,
                            reasonCode = ManualReason.SOURCE_UNAVAILABLE.name,
                            payload = mapOf("sources" to failures.keys.map { it.name }),
                        ),
                    )
                    jobs.complete(job.id)
                }
                else -> {
                    val next = minOf(now.plus(settings.sourceRetryDelay(job.attempts)), waitedSince.plus(settings.sourceTimeout))
                    transitions.commit(
                        application.move(
                            ApplicationEvent.SOURCE_FAILED,
                            Actor.SYSTEM,
                            now,
                            reasonCode = "SOURCE_UNAVAILABLE",
                            comment = failures.values.joinToString("; "),
                            payload = mapOf("sources" to failures.keys.map { it.name }, "nextAttemptAt" to next.toString()),
                        ),
                    )
                    jobs.retry(job.id, next, failures.values.joinToString("; "))
                }
            }
        }
    }

    /** T10/T11/T12: the scorecard and the credit policy; the decision is stored with all its inputs. */
    private fun score(job: PipelineJob, application: LoanApplication) {
        val stored = enrichment.load(application.id)
        val data = SourceData(
            registry = stored[SourceKind.REGISTRY] as RegistryInfo,
            tax = stored[SourceKind.TAX] as TaxInfo,
            bureau = stored[SourceKind.BUREAU] as BureauInfo,
            bank = stored[SourceKind.BANK] as BankInfo,
        )
        val rules = ruleSets.current()
        val product = products.find(application.terms.productCode)!!
        val now = clock.instant()
        val assessment = Scorecard.assess(application.terms, product, data, rules, now.atZone(ZONE).toLocalDate())
        val policy = CreditPolicy.decide(application.terms, product, products.all(), assessment, rules)
        val decision = Decision(
            applicationId = application.id,
            ruleSetVersion = rules.version,
            decidedByType = ActorType.SYSTEM,
            decidedBy = null,
            score = assessment.score,
            rating = assessment.rating,
            stopFactors = assessment.stopFactors,
            factors = assessment.factors,
            outcome = outcomeOf(policy),
            reasonCode = (policy as? PolicyDecision.Reject)?.reason?.name,
            manualReasons = (policy as? PolicyDecision.Manual)?.reasons.orEmpty(),
            recommendation = recommendationOf(policy),
            inputs = mapOf(
                "terms" to application.terms,
                "sources" to data,
                "annualRevenue" to assessment.annualRevenue,
                "revenueSource" to assessment.revenueSource.name,
                "existingMonthlyPayments" to assessment.existingMonthlyPayments,
            ),
            comment = null,
            createdAt = now,
        )
        tx.executeWithoutResult {
            decisions.save(decision)
            val summary = mapOf("score" to assessment.score, "rating" to assessment.rating.name, "decisionId" to decision.id.toString())
            when (policy) {
                is PolicyDecision.Approve -> issuer.issue(
                    application,
                    decision,
                    OfferKind.APPROVED,
                    policy.terms,
                    ApplicationEvent.AUTO_OFFER,
                    Actor.SYSTEM,
                    now,
                    summary,
                )
                is PolicyDecision.Counter -> issuer.issue(
                    application,
                    decision,
                    OfferKind.COUNTER,
                    policy.terms,
                    ApplicationEvent.AUTO_OFFER,
                    Actor.SYSTEM,
                    now,
                    summary + ("changes" to policy.changes),
                )
                is PolicyDecision.Manual ->
                    transitions.commit(
                        application.move(
                            ApplicationEvent.TO_MANUAL_REVIEW,
                            Actor.SYSTEM,
                            now,
                            reasonCode = policy.reasons.first().name,
                            payload = summary + ("reasons" to policy.reasons.map { it.name }),
                        ),
                    )
                is PolicyDecision.Reject ->
                    transitions.commit(
                        application.move(
                            ApplicationEvent.AUTO_REJECT,
                            Actor.SYSTEM,
                            now,
                            reasonCode = policy.reason.name,
                            comment = policy.explanation.joinToString("\n"),
                            payload = summary,
                        ),
                    )
            }
            jobs.complete(job.id)
        }
    }

    /** T19/T20: opens the loan in the core banking system; it is idempotent per application, so a retry is safe. */
    private fun disburse(job: PipelineJob, application: LoanApplication) {
        val offer = offers.current(application.id)!!
        val session = signing.latest(application.id)!!
        val booked = try {
            core.disburse(
                LoanRequest(
                    application.id,
                    application.company.inn,
                    offer.amount,
                    offer.termMonths,
                    offer.rate,
                    products.find(offer.productCode)!!.revolving,
                    session.signatureId!!,
                ),
            )
        } catch (exception: SourceUnavailableException) {
            val now = clock.instant()
            val next = now.plus(settings.sourceRetryDelay(job.attempts))
            tx.executeWithoutResult {
                transitions.commit(
                    application.move(ApplicationEvent.DISBURSEMENT_FAILED, Actor.SYSTEM, now, reasonCode = "CORE_UNAVAILABLE", comment = exception.message),
                )
                jobs.retry(job.id, next, exception.message)
            }
            return
        }
        val now = clock.instant()
        tx.executeWithoutResult {
            loans.save(
                Loan(
                    applicationId = application.id,
                    contractNumber = booked.contractNumber,
                    loanAccount = booked.loanAccount,
                    settlementAccount = booked.settlementAccount,
                    amount = offer.amount,
                    termMonths = offer.termMonths,
                    rate = offer.rate,
                    revolving = products.find(offer.productCode)!!.revolving,
                    disbursedAt = booked.disbursedAt,
                    maturityDate = booked.maturityDate,
                ),
            )
            transitions.commit(application.move(ApplicationEvent.DISBURSED, Actor.SYSTEM, now, payload = mapOf("contractNumber" to booked.contractNumber)))
            jobs.complete(job.id)
        }
    }

    /** T6: the client did not answer by the deadline. A newer request moves the deadline, and the job with it. */
    private fun expireInfoRequest(job: PipelineJob, application: LoanApplication) {
        val request = infoRequests.open(application.id)
        val now = clock.instant()
        tx.executeWithoutResult {
            when {
                request == null -> jobs.complete(job.id)
                request.deadline.isAfter(now) -> jobs.retry(job.id, request.deadline, null)
                else -> {
                    infoRequests.resolve(request.id, now, null)
                    transitions.commit(application.move(ApplicationEvent.INFO_EXPIRED, Actor.SYSTEM, now))
                    jobs.complete(job.id)
                }
            }
        }
    }

    /** T17: the offer was neither accepted nor declined in time. */
    private fun expireOffer(job: PipelineJob, application: LoanApplication) {
        val offer = offers.current(application.id)
        val now = clock.instant()
        tx.executeWithoutResult {
            when {
                offer == null || offer.status != OfferStatus.ISSUED -> jobs.complete(job.id)
                offer.validUntil.isAfter(now) -> jobs.retry(job.id, offer.validUntil, null)
                else -> {
                    offers.updateStatus(offer.id, OfferStatus.EXPIRED, now)
                    transitions.commit(application.move(ApplicationEvent.OFFER_EXPIRED, Actor.SYSTEM, now))
                    jobs.complete(job.id)
                }
            }
        }
    }

    private fun manualWithoutScore(application: LoanApplication, silent: Set<SourceKind>, now: Instant) =
        Decision(
            applicationId = application.id,
            ruleSetVersion = ruleSets.current().version,
            decidedByType = ActorType.SYSTEM,
            decidedBy = null,
            score = null,
            rating = null,
            stopFactors = emptyList(),
            factors = emptyList(),
            outcome = DecisionOutcome.MANUAL_REVIEW,
            reasonCode = ManualReason.SOURCE_UNAVAILABLE.name,
            manualReasons = listOf(ManualReason.SOURCE_UNAVAILABLE),
            recommendation = null,
            inputs = mapOf("terms" to application.terms, "silentSources" to silent.map { it.name }),
            comment = "Не ответили: ${silent.joinToString { it.title }}",
            createdAt = now,
        )

    private fun outcomeOf(policy: PolicyDecision) =
        when (policy) {
            is PolicyDecision.Approve -> DecisionOutcome.APPROVE
            is PolicyDecision.Counter -> DecisionOutcome.COUNTER_OFFER
            is PolicyDecision.Manual -> DecisionOutcome.MANUAL_REVIEW
            is PolicyDecision.Reject -> DecisionOutcome.REJECT
        }

    private fun recommendationOf(policy: PolicyDecision?): Recommendation? =
        when (policy) {
            null -> null
            is PolicyDecision.Approve -> Recommendation(DecisionOutcome.APPROVE, terms = policy.terms)
            is PolicyDecision.Counter -> Recommendation(DecisionOutcome.COUNTER_OFFER, terms = policy.terms, notes = policy.changes)
            is PolicyDecision.Reject -> Recommendation(DecisionOutcome.REJECT, rejectReason = policy.reason, notes = policy.explanation)
            is PolicyDecision.Manual -> recommendationOf(policy.recommendation)
        }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Europe/Moscow")
    }
}
