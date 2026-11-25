package ru.creditcabinet.agent.application

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import ru.creditcabinet.agent.domain.AgentAccessPort
import ru.creditcabinet.agent.domain.AgentPortfolioCalculator
import ru.creditcabinet.agent.domain.AgentPortfolioSummary
import ru.creditcabinet.application.domain.Actor
import ru.creditcabinet.application.domain.ActorType
import ru.creditcabinet.application.domain.ApplicationEvent
import ru.creditcabinet.application.domain.ApplicationJournal
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.CommissionStatus
import ru.creditcabinet.application.domain.InfoRequests
import ru.creditcabinet.application.domain.JournalNote
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.domain.PipelineJobs
import ru.creditcabinet.application.domain.PipelineStep
import ru.creditcabinet.application.service.ApplicationService
import ru.creditcabinet.application.service.notFound
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.document.DocumentService
import ru.creditcabinet.document.domain.ApplicationDocument
import ru.creditcabinet.document.domain.DocumentRepository
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.document.domain.StoredFile
import ru.creditcabinet.preapproved.application.PreApprovedOfferService
import ru.creditcabinet.preapproved.domain.PreApprovedOffer
import ru.creditcabinet.product.domain.Product
import java.time.Clock
import java.util.UUID

@Service
class AgentApplicationService(
    private val agentAccess: AgentAccessPort,
    private val applications: ApplicationRepository,
    private val applicationService: ApplicationService,
    private val journal: ApplicationJournal,
    private val jobs: PipelineJobs,
    private val infoRequests: InfoRequests,
    private val documentRepository: DocumentRepository,
    private val documentService: DocumentService,
    private val preApprovedOffers: PreApprovedOfferService,
    private val clock: Clock,
) {
    fun portfolio(): AgentPortfolioSummary {
        val caller = CurrentCaller.requireRole(Roles.AGENT)
        val companyIds = agentAccess.listCompanyIds(caller.userId).toSet()
        val visible = applications.listByAgent(caller.userId).filter { it.company.id in companyIds }
        return AgentPortfolioCalculator.summarize(companyIds.size, visible)
    }

    fun listPreApprovedOffers(): List<PreApprovedOffer> {
        val caller = CurrentCaller.requireRole(Roles.AGENT)
        val companyIds = agentAccess.listCompanyIds(caller.userId)
        return preApprovedOffers.listForCompanies(companyIds)
    }

    @Transactional
    fun applyPreApprovedOffer(offerId: UUID): LoanApplication {
        val caller = CurrentCaller.requireRole(Roles.AGENT)
        val companyIds = agentAccess.listCompanyIds(caller.userId)
        return preApprovedOffers.applyForAgent(offerId, caller.userId, companyIds, caller.fullName)
    }

    fun listApplications(statuses: Set<ApplicationStatus>? = null): List<LoanApplication> {
        val caller = CurrentCaller.requireRole(Roles.AGENT)
        val companyIds = agentAccess.listCompanyIds(caller.userId).toSet()
        return applications.listByAgent(caller.userId)
            .filter { it.company.id in companyIds && (statuses.isNullOrEmpty() || it.status in statuses) }
    }

    @Transactional
    fun create(companyId: UUID, terms: ApplicationTerms): LoanApplication {
        val caller = CurrentCaller.requireRole(Roles.AGENT)
        val access = agentAccess.checkAccess(caller.userId, companyId)
        val company = access.company?.takeIf { access.allowed } ?: throw notFound()
        val product = applicationService.product(terms.productCode)
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
            applications.save(draft)
        } catch (_: DataIntegrityViolationException) {
            throw activeExists(product)
        }
        journal.append(saved.note(JournalNote.CREATED, Actor(ActorType.AGENT, caller.userId), now))
        return saved
    }

    fun get(id: UUID): LoanApplication = ownAgentApplication(id).second

    @Transactional
    fun updateDraft(id: UUID, terms: ApplicationTerms): LoanApplication {
        val (_, application) = ownAgentApplication(id)
        val product = applicationService.product(application.terms.productCode)
        require(terms.purpose in product.purposes) { "Продукт «${product.name}» не подходит для этой цели" }
        val sanitizedTerms = terms.copy(bureauConsent = false)
        val updated = preApprovedOffers.detachIfOutOfBounds(application.updateDraft(sanitizedTerms, clock.instant()), sanitizedTerms)
        return applications.save(updated)
    }

    @Transactional
    fun requestClientConfirmation(id: UUID): LoanApplication {
        val (caller, application) = ownAgentApplication(id)
        val product = applicationService.product(application.terms.productCode)
        return applicationService.commit(
            application.requestClientConfirmation(product, Actor(ActorType.AGENT, caller.userId), clock.instant()),
        )
    }

    @Transactional
    fun cancel(id: UUID): LoanApplication {
        val (caller, application) = ownAgentApplication(id)
        val saved = applicationService.commit(application.agentCancel(Actor(ActorType.AGENT, caller.userId), clock.instant()))
        jobs.cancelPending(saved.id)
        return saved
    }

    @Transactional
    fun provideInfo(id: UUID, answer: String?): LoanApplication {
        val (caller, application) = ownAgentApplication(id)
        val request = infoRequests.open(application.id) ?: throw ConflictException("Банк сейчас ничего не запрашивал")
        val fresh = documentRepository.list(application.id).filter { !it.uploadedAt.isBefore(request.createdAt) }.map { it.type }.toSet()
        val missing = request.items.mapNotNull { it.documentType }.filter { it !in fresh }
        require(missing.isEmpty()) { "Загрузите: ${missing.joinToString(", ") { it.title.lowercase() }}" }
        require(!request.hasQuestions || !answer.isNullOrBlank()) { "Ответьте на вопросы банка" }
        val now = clock.instant()
        infoRequests.resolve(request.id, now, answer?.take(MAX_COMMENT))
        val saved = applicationService.commit(
            application.move(ApplicationEvent.INFO_PROVIDED, Actor(ActorType.AGENT, caller.userId), now, comment = answer?.take(MAX_COMMENT)),
        )
        jobs.enqueue(saved.id, PipelineStep.DATA_CHECK, now)
        return saved
    }

    fun listDocuments(id: UUID): List<ApplicationDocument> {
        val (_, application) = ownAgentApplication(id)
        return documentRepository.list(application.id)
    }

    @Transactional
    fun uploadDocument(id: UUID, type: DocumentType, file: MultipartFile): ApplicationDocument {
        val (caller, application) = ownAgentApplication(id)
        return documentService.uploadForAgent(application, caller, type, file)
    }

    @Transactional
    fun deleteDocument(id: UUID, documentId: UUID) {
        val (caller, application) = ownAgentApplication(id)
        documentService.deleteForAgent(application, caller, documentId)
    }

    fun openDocument(id: UUID, documentId: UUID): Pair<ApplicationDocument, StoredFile> {
        CurrentCaller.requireRole(Roles.AGENT)
        // DocumentService.open delegates to ApplicationService.get(id), which enforces agentId + mandate check once.
        return documentService.open(id, documentId)
    }

    /**
     * Strict double-check on every agent operation:
     * 1) application.agentId == caller.userId
     * 2) agentAccess.checkAccess(caller.userId, application.company.id).allowed == true
     * Any violation returns 404 Not Found without leaking existence.
     */
    fun ownAgentApplication(id: UUID): Pair<TokenClaims, LoanApplication> {
        val caller = CurrentCaller.requireRole(Roles.AGENT)
        val application = applications.find(id) ?: throw notFound()
        if (application.agentId != caller.userId) throw notFound()
        if (!agentAccess.checkAccess(caller.userId, application.company.id).allowed) throw notFound()
        return caller to application
    }

    private fun activeExists(product: Product) =
        ConflictException("По продукту «${product.name}» у компании уже есть заявка в работе — продолжите её или отзовите")

    private companion object {
        const val MAX_COMMENT = 2000
    }
}
