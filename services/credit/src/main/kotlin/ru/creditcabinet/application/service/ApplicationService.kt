package ru.creditcabinet.application.service

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.agent.application.AgentClientEligibility
import ru.creditcabinet.agent.domain.AgentAccessPort
import ru.creditcabinet.application.application.ApplicationOperations
import ru.creditcabinet.application.application.CommitTransition
import ru.creditcabinet.application.application.CreditCaller
import ru.creditcabinet.application.application.CreditFailure.Conflict
import ru.creditcabinet.application.application.CreditFailure.NotFound
import ru.creditcabinet.application.domain.ApplicationCreationConflict
import ru.creditcabinet.application.domain.ApplicationEvent
import ru.creditcabinet.application.domain.ApplicationJournal
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.CompanyDirectory
import ru.creditcabinet.application.domain.IdempotencyKeys
import ru.creditcabinet.application.domain.JournalEntry
import ru.creditcabinet.application.domain.JournalNote
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.domain.PipelineJobs
import ru.creditcabinet.application.domain.PipelineStep
import ru.creditcabinet.application.domain.Transition
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.financing.scenario.application.FinancingScenarios
import ru.creditcabinet.preapproved.application.PreApprovedOfferService
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductCatalog
import ru.creditcabinet.product.domain.SubmissionProductCatalog
import ru.creditcabinet.product.domain.SubmissionProductSnapshot
import java.time.Clock
import java.util.UUID

@Service
class ApplicationService(
    private val applications: ApplicationRepository,
    private val journal: ApplicationJournal,
    private val jobs: PipelineJobs,
    private val idempotencyKeys: IdempotencyKeys,
    private val companies: CompanyDirectory,
    private val products: ProductCatalog,
    private val submissionCatalog: SubmissionProductCatalog,
    private val clock: Clock,
    private val transitions: CommitTransition,
    private val infoRequests: ru.creditcabinet.application.domain.InfoRequests,
    private val documents: ru.creditcabinet.document.domain.DocumentRepository,
    private val preApprovedOffers: PreApprovedOfferService,
    private val agentAccess: AgentAccessPort,
    private val agentEligibility: AgentClientEligibility,
    private val scenarios: FinancingScenarios,
    private val callerContext: CreditCaller,
) : ApplicationOperations {
    /** T1: a draft for the caller's company. One application in progress per company and product. */
    @Transactional
    override fun create(terms: ApplicationTerms): LoanApplication {
        val (claims, companyId) = callerContext.requireClient()
        val product = product(terms.productCode)
        require(terms.purpose in product.purposes) { "Продукт «${product.name}» не подходит для этой цели" }
        if (applications.hasActive(companyId, product.code)) throw activeExists(product)
        val company = companies.find(companyId) ?: throw NotFound("Компания не найдена")
        val now = clock.instant()
        val saved = try {
            applications.create(LoanApplication(company = company, createdBy = claims.userId, terms = terms, createdAt = now, productVersion = product.version))
        } catch (_: ApplicationCreationConflict) {
            // The same product requested twice at the same moment: the unique index decided.
            throw activeExists(product)
        }
        journal.append(saved.note(JournalNote.CREATED, actorOf(claims), now))
        return saved
    }

    @Transactional
    override fun updateDraft(id: UUID, terms: ApplicationTerms, expectedVersion: Long?): LoanApplication {
        val (_, application) = own(id)
        if (application.status == ApplicationStatus.DRAFT && application.activeAgentId != null) throw notFound()
        if (application.agentId != null) {
            throw Conflict("Черновик агентской заявки может редактировать только агент")
        }
        application.requireDraftVersion(expectedVersion)
        val product = product(application.terms.productCode)
        require(terms.purpose in product.purposes) { "Продукт «${product.name}» не подходит для этой цели" }
        val updated = preApprovedOffers.detachIfOutOfBounds(application.updateDraft(terms, clock.instant()), terms)
        return applications.save(updated)
    }

    /**
     * T2. With an Idempotency-Key a repeated request (a double click, a retry after a timeout)
     * returns the application as it is instead of a second attempt and its 409.
     */
    @Transactional
    override fun submit(id: UUID, idempotencyKey: String?, acceptedProductVersion: Long?, expectedVersion: Long?): LoanApplication {
        val (claims, application) = own(id, forUpdate = true)
        if (application.agentId != null) {
            throw Conflict("Агентскую заявку нельзя отправить напрямую — сначала требуется подтверждение клиента")
        }
        if (idempotencyKey != null) {
            val previous = idempotencyKeys.find(claims.userId, idempotencyKey, SUBMIT)
            if (previous == id) return application
            if (previous != null) throw Conflict("Этот Idempotency-Key уже использован для другой заявки")
        }
        expectedVersion?.let(application::requireDraftVersion)
        val snapshot = submissionSnapshot(application, acceptedProductVersion)
        val selected = snapshot.selected
        val pinned = application.copy(productVersion = selected.version, productCatalogSnapshot = snapshot.versions)
        val now = clock.instant()
        val saved = commit(pinned.submit(selected, actorOf(claims), now))
        jobs.enqueue(saved.id, PipelineStep.DATA_CHECK, now)
        idempotencyKey?.let { idempotencyKeys.record(claims.userId, it, SUBMIT, saved.id) }
        return saved
    }

    /** T23: client reviews an agent-prepared application, grants bureau consent, and submits to the bank. */
    @Transactional
    override fun confirmByClient(
        id: UUID,
        bureauConsent: Boolean,
        acceptedProductVersion: Long?,
        acceptedScenarioRevision: Long?,
    ): LoanApplication {
        val (claims, current) = own(id, forUpdate = true)
        val application = if (current.status == ApplicationStatus.DRAFT) {
            if (!canResumeIndependently(current)) throw Conflict("Заявка ожидает повторной передачи агентом")
            scenarios.restoreHandoff(current).copy(clientManaged = true)
        } else {
            current
        }
        if (scenarios.confirm(application, acceptedScenarioRevision)) return current
        val snapshot = submissionSnapshot(application, acceptedProductVersion)
        val selected = snapshot.selected
        if (acceptedScenarioRevision != null && selected.version != application.productVersion) {
            throw Conflict("Условия продукта изменились — обновите заявку, проверьте новые условия и подтвердите их перед отправкой")
        }
        val commissionRateBps = if (application.agentId != null) selected.agentCommissionRateBps else application.agentCommissionRateBps
        val commissionAmount = if (application.agentId != null && commissionRateBps != null) {
            application.terms.amount * commissionRateBps / 10_000L
        } else {
            application.agentCommissionAmount
        }
        val pinned = application.copy(
            productVersion = selected.version,
            productCatalogSnapshot = snapshot.versions,
            agentCommissionRateBps = commissionRateBps,
            agentCommissionAmount = commissionAmount,
        )
        val now = clock.instant()
        val saved = commit(pinned.confirmAndSubmit(selected, bureauConsent, actorOf(claims), now))
        jobs.enqueue(saved.id, PipelineStep.DATA_CHECK, now)
        return saved
    }

    /** T24: client returns an agent-prepared application to the agent for corrections. */
    @Transactional
    override fun returnToAgentByClient(id: UUID, comment: String?): LoanApplication {
        val (claims, application) = own(id, forUpdate = true)
        return commit(
            application.returnToAgent(
                actorOf(claims),
                comment?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_COMMENT),
                clock.instant(),
            ),
        )
    }

    /** T21: the client stops the application; pending pipeline steps are cancelled with it. */
    @Transactional
    override fun withdraw(id: UUID, comment: String?): LoanApplication {
        val (claims, application) = own(id, forUpdate = true)
        if (application.status == ApplicationStatus.DRAFT && application.activeAgentId != null) throw notFound()
        val saved = commit(application.move(ApplicationEvent.WITHDRAW, actorOf(claims), clock.instant(), comment = comment?.take(MAX_COMMENT)))
        if (application.status in setOf(ApplicationStatus.DRAFT, ApplicationStatus.PENDING_CLIENT_CONFIRMATION)) {
            application.preApprovedOfferId?.let { preApprovedOffers.release(it, saved.id) }
        }
        jobs.cancelPending(saved.id)
        return saved
    }

    /**
     * T5: the client did what the bank asked. Every requested document must have been uploaded
     * after the request, and questions need an answer; then the completeness check runs again.
     */
    @Transactional
    override fun provideInfo(id: UUID, answer: String?): LoanApplication {
        val (claims, application) = own(id, forUpdate = true)
        val request = infoRequests.open(application.id) ?: throw Conflict("Банк сейчас ничего не запрашивал")
        val fresh = documents.list(application.id).filter { !it.uploadedAt.isBefore(request.createdAt) }.map { it.type }.toSet()
        val missing = request.items.mapNotNull { it.documentType }.filter { it !in fresh }
        require(missing.isEmpty()) { "Загрузите: ${missing.joinToString(", ") { it.title.lowercase() }}" }
        require(!request.hasQuestions || !answer.isNullOrBlank()) { "Ответьте на вопросы банка" }
        val now = clock.instant()
        infoRequests.resolve(request.id, now, answer?.take(MAX_COMMENT))
        val saved = commit(application.move(ApplicationEvent.INFO_PROVIDED, actorOf(claims), now, comment = answer?.take(MAX_COMMENT)))
        jobs.enqueue(saved.id, PipelineStep.DATA_CHECK, now)
        return saved
    }

    override fun get(id: UUID): LoanApplication {
        val claims = callerContext.claims()
        val application = applications.find(id)?.takeIf { claims.canSee(it) } ?: throw notFound()
        if (claims.isAgent) {
            if (!agentAccess.checkAccess(claims.userId, application.company.id).allowed || !agentEligibility.isEligible(application.company)) {
                throw notFound()
            }
        }
        return application
    }

    /** A client sees their company's applications; bank staff the queue without drafts. */
    override fun list(statuses: Set<ApplicationStatus>?): List<LoanApplication> {
        val claims = callerContext.claims()
        return when {
            claims.isClient -> {
                val (_, companyId) = callerContext.requireClient()
                applications.listByCompany(companyId).filter { it.visibleToClient && (statuses.isNullOrEmpty() || it.status in statuses) }
            }
            else -> {
                callerContext.requireBankStaff()
                applications.listForBank(statuses)
            }
        }
    }

    @Transactional(readOnly = true)
    override fun journal(id: UUID): List<JournalEntry> {
        val application = get(id)
        val entries = journal.list(id)
        if (application.agentId == null || callerContext.claims().isAgent || !scenarios.hasHandedPacket(id)) return entries
        val readableIds = documents.listForPacket(id, application.submittedAt).mapTo(mutableSetOf()) { it.id.toString() }
        return entries.map { entry ->
            val documentNote = entry.event == JournalNote.DOCUMENT_ADDED.name || entry.event == JournalNote.DOCUMENT_REMOVED.name
            if (documentNote && entry.payload["documentId"]?.toString() !in readableIds) entry.copy(payload = emptyMap()) else entry
        }
    }

    fun product(code: String): Product = products.forDraft(code) ?: throw NotFound("Продукт не найден")

    /** The application of the caller's company, for changing it: clients only (hiding private agent drafts). */
    override fun own(id: UUID, forUpdate: Boolean): Pair<TokenClaims, LoanApplication> {
        val (claims, companyId) = callerContext.requireClient()
        val current = if (forUpdate) applications.findForUpdate(id) else applications.find(id)
        val application = current?.takeIf { it.company.id == companyId && it.visibleToClient } ?: throw notFound()
        return claims to application
    }

    override fun canResumeIndependently(application: LoanApplication): Boolean {
        val agentId = application.activeAgentId ?: return false
        return application.status == ApplicationStatus.DRAFT &&
            application.clientHandoffAt != null &&
            !agentAccess.checkAccess(agentId, application.company.id).allowed
    }

    override fun clientReadView(application: LoanApplication): LoanApplication =
        if (application.status == ApplicationStatus.DRAFT && application.clientHandoffAt != null) {
            scenarios.restoreHandoff(application)
        } else {
            application
        }

    fun commit(transition: Transition): LoanApplication = transitions.commit(transition)

    private fun submissionSnapshot(application: LoanApplication, acceptedVersion: Long?): SubmissionProductSnapshot {
        val snapshot = submissionCatalog.snapshotForSubmission(application.terms.productCode)
            ?: throw Conflict("Продукт выключен из каталога — выберите другой продукт и создайте новую заявку")
        if ((acceptedVersion ?: application.productVersion) != snapshot.selected.version) {
            throw Conflict("Условия продукта изменились — обновите заявку, проверьте новые условия и подтвердите их перед отправкой")
        }
        return snapshot
    }

    private fun actorOf(claims: TokenClaims) = claims.toActor() ?: throw Conflict("Это действие недоступно для вашей роли")

    private fun activeExists(product: Product) =
        Conflict("По продукту «${product.name}» у компании уже есть заявка в работе — продолжите её или отзовите")

    private companion object {
        const val SUBMIT = "SUBMIT"
        const val MAX_COMMENT = 2000
    }
}
