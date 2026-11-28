package ru.creditcabinet.agent.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.agent.domain.ActionOwner
import ru.creditcabinet.agent.domain.AgentAccessPort
import ru.creditcabinet.agent.domain.AgentPortfolioCalculator
import ru.creditcabinet.agent.domain.AgentPortfolioSummary
import ru.creditcabinet.application.application.CommitTransition
import ru.creditcabinet.application.application.CreditCaller
import ru.creditcabinet.application.application.CreditFailure.Conflict
import ru.creditcabinet.application.application.CreditFailure.NotFound
import ru.creditcabinet.application.domain.Actor
import ru.creditcabinet.application.domain.ActorType
import ru.creditcabinet.application.domain.ApplicationCreationConflict
import ru.creditcabinet.application.domain.ApplicationEvent
import ru.creditcabinet.application.domain.ApplicationJournal
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.CommissionStatus
import ru.creditcabinet.application.domain.InfoRequests
import ru.creditcabinet.application.domain.JournalEntry
import ru.creditcabinet.application.domain.JournalNote
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.domain.PipelineJobs
import ru.creditcabinet.application.domain.PipelineStep
import ru.creditcabinet.application.service.notFound
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.document.application.AgentDocuments
import ru.creditcabinet.document.application.DocumentUpload
import ru.creditcabinet.document.domain.ApplicationDocument
import ru.creditcabinet.document.domain.DocumentRepository
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.document.domain.StoredFile
import ru.creditcabinet.financing.scenario.application.FinancingScenarios
import ru.creditcabinet.financing.scenario.domain.ScenarioInputs
import ru.creditcabinet.financing.scenario.domain.ScenarioSnapshot
import ru.creditcabinet.offer.OfferRepository
import ru.creditcabinet.preapproved.application.PreApprovedOfferService
import ru.creditcabinet.preapproved.domain.PreApprovedOffer
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductCatalog
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class AgentApplicationAction(val owner: ActionOwner, val label: String, val deadline: Instant? = null)

@Service
class AgentApplicationService(
    private val agentAccess: AgentAccessPort,
    private val applications: ApplicationRepository,
    private val transitions: CommitTransition,
    private val products: ProductCatalog,
    private val journal: ApplicationJournal,
    private val jobs: PipelineJobs,
    private val infoRequests: InfoRequests,
    private val documentRepository: DocumentRepository,
    private val documentService: AgentDocuments,
    private val preApprovedOffers: PreApprovedOfferService,
    private val clock: Clock,
    private val callerIdentity: CreditCaller,
    private val eligibility: AgentClientEligibility,
    private val scenarios: FinancingScenarios,
    private val offers: OfferRepository,
) {
    private fun draftProduct(code: String): Product = products.forDraft(code) ?: throw NotFound("Продукт не найден")

    @Transactional
    fun saveFinancingScenario(id: UUID, inputs: ScenarioInputs): ScenarioSnapshot {
        val (_, application) = ownAgentApplication(id, forUpdate = true)
        return scenarios.saveDraft(application, inputs)
    }

    fun portfolio(): AgentPortfolioSummary {
        val caller = callerIdentity.requireRole(Roles.AGENT)
        val companyIds = agentAccess.listCompanyIds(caller.userId).toSet()
        val visible = eligibleApplications(caller.userId, companyIds)
        return AgentPortfolioCalculator.summarize(companyIds.size, visible)
    }

    fun listPreApprovedOffers(): List<PreApprovedOffer> {
        val caller = callerIdentity.requireRole(Roles.AGENT)
        val companyIds = agentAccess.listCompanyIds(caller.userId)
        val offers = preApprovedOffers.listForCompanies(companyIds)
        val eligible = offers.map { it.companyId }.distinct().filter { companyId ->
            val access = agentAccess.checkAccess(caller.userId, companyId)
            access.allowed && access.company?.let(eligibility::isEligible) == true
        }.toSet()
        return offers.filter { it.companyId in eligible }
    }

    @Transactional
    fun applyPreApprovedOffer(offerId: UUID): LoanApplication {
        val caller = callerIdentity.requireRole(Roles.AGENT)
        val companyIds = agentAccess.listCompanyIds(caller.userId)
        return preApprovedOffers.applyForAgent(offerId, caller.userId, companyIds, caller.fullName)
    }

    fun listApplications(statuses: Set<ApplicationStatus>? = null): List<LoanApplication> {
        val caller = callerIdentity.requireRole(Roles.AGENT)
        return listForAgent(caller.userId)
            .filter { statuses.isNullOrEmpty() || it.status in statuses }
    }

    internal fun listForAgent(agentId: UUID): List<LoanApplication> =
        eligibleApplications(agentId, agentAccess.listCompanyIds(agentId).toSet())

    private fun eligibleApplications(agentId: UUID, companyIds: Set<UUID>): List<LoanApplication> {
        val owned = applications.listByAgent(agentId).filter { it.activeAgentId == agentId && it.company.id in companyIds }
        val eligible = owned.map { it.company }.distinctBy { it.id }.filter(eligibility::isEligible).map { it.id }.toSet()
        return owned.filter { it.company.id in eligible }
    }

    @Transactional
    fun create(companyId: UUID, terms: ApplicationTerms): LoanApplication {
        val caller = callerIdentity.requireRole(Roles.AGENT)
        val access = agentAccess.checkAccess(caller.userId, companyId)
        val company = access.company?.takeIf { access.allowed } ?: throw notFound()
        eligibility.requireEligible(company)
        val product = draftProduct(terms.productCode)
        require(terms.purpose in product.purposes) { "Продукт «${product.name}» не подходит для этой цели" }
        if (applications.hasActive(companyId, product.code)) throw activeExists(product)
        val now = clock.instant()
        val commissionRateBps = product.agentCommissionRateBps
        val commissionAmount = terms.amount * commissionRateBps / 10_000L
        val draft = LoanApplication(
            company = company,
            createdBy = caller.userId,
            terms = terms.copy(bureauConsent = false),
            createdAt = now,
            productVersion = product.version,
            agentId = caller.userId,
            agentName = caller.fullName,
            agentCommissionRateBps = commissionRateBps,
            agentCommissionAmount = commissionAmount,
            agentCommissionStatus = CommissionStatus.EXPECTED,
        )
        val saved = try {
            applications.create(draft)
        } catch (_: ApplicationCreationConflict) {
            throw activeExists(product)
        }
        journal.append(saved.note(JournalNote.CREATED, Actor(ActorType.AGENT, caller.userId), now))
        return saved
    }

    fun get(id: UUID): LoanApplication = ownAgentApplication(id).second

    fun events(id: UUID): List<JournalEntry> =
        journal.list(get(id).id).filterNot { it.event == JournalNote.PRIVATE_DOCUMENT_ADDED.name }

    fun action(application: LoanApplication): AgentApplicationAction? =
        when (application.status) {
            ApplicationStatus.DRAFT -> AgentApplicationAction(ActionOwner.AGENT, "Подготовить пакет")
            ApplicationStatus.PENDING_CLIENT_CONFIRMATION -> AgentApplicationAction(ActionOwner.CLIENT, "Подтвердить пакет")
            ApplicationStatus.INFO_REQUESTED -> infoRequests.open(application.id)?.let { request ->
                if (request.items.any { it.documentType == DocumentType.BANK_STATEMENT }) {
                    AgentApplicationAction(ActionOwner.CLIENT, "Ответить на запрос по выписке", request.deadline)
                } else {
                    AgentApplicationAction(ActionOwner.AGENT, "Ответить на запрос банка", request.deadline)
                }
            }
            ApplicationStatus.MANUAL_REVIEW -> AgentApplicationAction(ActionOwner.BANK, "Рассмотреть заявку")
            ApplicationStatus.OFFER_ISSUED ->
                AgentApplicationAction(ActionOwner.CLIENT, "Принять предложение", offers.current(application.id)?.validUntil)
            ApplicationStatus.SIGNING -> AgentApplicationAction(ActionOwner.CLIENT, "Подписать договор")
            ApplicationStatus.SUBMITTED, ApplicationStatus.ENRICHMENT, ApplicationStatus.SCORING, ApplicationStatus.DISBURSING ->
                AgentApplicationAction(ActionOwner.SYSTEM, "Обработать заявку")
            else -> null
        }

    @Transactional
    fun updateDraft(id: UUID, terms: ApplicationTerms): LoanApplication {
        val (_, application) = ownAgentApplication(id, forUpdate = true)
        val product = draftProduct(application.terms.productCode)
        require(terms.purpose in product.purposes) { "Продукт «${product.name}» не подходит для этой цели" }
        val sanitizedTerms = terms.copy(bureauConsent = false)
        val refreshed = application.copy(
            productVersion = product.version,
            agentCommissionRateBps = product.agentCommissionRateBps,
        )
        val updated = preApprovedOffers.detachIfOutOfBounds(refreshed.updateDraft(sanitizedTerms, clock.instant()), sanitizedTerms)
        return applications.save(updated)
    }

    @Transactional
    fun requestClientConfirmation(id: UUID): LoanApplication {
        val (caller, application) = ownAgentApplication(id, forUpdate = true)
        val product = draftProduct(application.terms.productCode)
        val commissionRateBps = product.agentCommissionRateBps
        val refreshed = application.copy(
            productVersion = product.version,
            agentCommissionRateBps = commissionRateBps,
            agentCommissionAmount = application.terms.amount * commissionRateBps / 10_000L,
        )
        val prepared = scenarios.prepareHandoff(refreshed, product)
        val ready = preApprovedOffers.detachIfOutOfBounds(prepared, prepared.terms)
        return transitions.commit(
            ready.requestClientConfirmation(product, Actor(ActorType.AGENT, caller.userId), clock.instant()),
        )
    }

    @Transactional
    fun cancel(id: UUID): LoanApplication {
        val (caller, application) = ownAgentApplication(id, forUpdate = true)
        val saved = transitions.commit(application.agentCancel(Actor(ActorType.AGENT, caller.userId), clock.instant()))
        if (application.status in setOf(ApplicationStatus.DRAFT, ApplicationStatus.PENDING_CLIENT_CONFIRMATION)) {
            application.preApprovedOfferId?.let { preApprovedOffers.release(it, saved.id) }
        }
        jobs.cancelPending(saved.id)
        return saved
    }

    @Transactional
    fun provideInfo(id: UUID, answer: String?): LoanApplication {
        val (caller, application) = ownAgentApplication(id, forUpdate = true)
        val request = infoRequests.open(application.id) ?: throw Conflict("Банк сейчас ничего не запрашивал")
        val submitted = documentRepository.list(application.id).filter { !it.uploadedAt.isBefore(request.createdAt) }
        if (submitted.any { file -> file.privateToBank && request.items.any { it.documentType == file.type } }) {
            throw Conflict("Завершить запрос по приватной выписке может клиент")
        }
        val fresh = submitted.map { it.type }.toSet()
        val missing = request.items.mapNotNull { it.documentType }.filter { it !in fresh }
        require(missing.isEmpty()) { "Загрузите: ${missing.joinToString(", ") { it.title.lowercase() }}" }
        require(!request.hasQuestions || !answer.isNullOrBlank()) { "Ответьте на вопросы банка" }
        val now = clock.instant()
        infoRequests.resolve(request.id, now, answer?.take(MAX_COMMENT))
        val saved = transitions.commit(
            application.move(ApplicationEvent.INFO_PROVIDED, Actor(ActorType.AGENT, caller.userId), now, comment = answer?.take(MAX_COMMENT)),
        )
        jobs.enqueue(saved.id, PipelineStep.DATA_CHECK, now)
        return saved
    }

    fun listDocuments(id: UUID): List<ApplicationDocument> {
        val (_, application) = ownAgentApplication(id)
        return documentRepository.list(application.id).filterNot { it.privateToBank }
    }

    @Transactional
    fun uploadDocument(id: UUID, type: DocumentType, file: DocumentUpload): ApplicationDocument {
        val (caller, application) = ownAgentApplication(id, forUpdate = true)
        return documentService.uploadForAgent(application, caller, type, file)
    }

    @Transactional
    fun deleteDocument(id: UUID, documentId: UUID) {
        val (caller, application) = ownAgentApplication(id, forUpdate = true)
        documentService.deleteForAgent(application, caller, documentId)
    }

    fun openDocument(id: UUID, documentId: UUID): Pair<ApplicationDocument, StoredFile> {
        callerIdentity.requireRole(Roles.AGENT)
        // DocumentService.open delegates to ApplicationService.get(id), which enforces agentId + mandate check once.
        return documentService.open(id, documentId)
    }

    /**
     * Strict double-check on every agent operation:
     * 1) application.agentId == caller.userId
     * 2) agentAccess.checkAccess(caller.userId, application.company.id).allowed == true
     * Any violation returns 404 Not Found without leaking existence.
     */
    fun ownAgentApplication(id: UUID): Pair<TokenClaims, LoanApplication> = ownAgentApplication(id, forUpdate = false)

    private fun ownAgentApplication(id: UUID, forUpdate: Boolean): Pair<TokenClaims, LoanApplication> {
        val caller = callerIdentity.requireRole(Roles.AGENT)
        return caller to authorizedCase(id, caller.userId, forUpdate)
    }

    internal fun authorizedCase(id: UUID, agentId: UUID, forUpdate: Boolean = false): LoanApplication {
        val application = (if (forUpdate) applications.findForUpdate(id) else applications.find(id)) ?: throw notFound()
        if (application.activeAgentId != agentId) throw notFound()
        if (!agentAccess.checkAccess(agentId, application.company.id).allowed) throw notFound()
        if (!eligibility.isEligible(application.company)) throw notFound()
        return application
    }

    private fun activeExists(product: Product) =
        Conflict("По продукту «${product.name}» у компании уже есть заявка в работе — продолжите её или отзовите")

    private companion object {
        const val MAX_COMMENT = 2000
    }
}
