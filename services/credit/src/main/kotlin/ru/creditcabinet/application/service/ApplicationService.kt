package ru.creditcabinet.application.service

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.agent.application.AgentClientEligibility
import ru.creditcabinet.agent.domain.AgentAccessPort
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
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.financing.scenario.FinancingScenarios
import ru.creditcabinet.preapproved.application.PreApprovedOfferService
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductCatalog
import ru.creditcabinet.product.domain.forApplication
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
    private val clock: Clock,
    private val transitions: Transitions,
    private val infoRequests: ru.creditcabinet.application.domain.InfoRequests,
    private val documents: ru.creditcabinet.document.domain.DocumentRepository,
    private val preApprovedOffers: PreApprovedOfferService,
    private val agentAccess: AgentAccessPort,
    private val agentEligibility: AgentClientEligibility,
    private val scenarios: FinancingScenarios,
) {
    /** T1: a draft for the caller's company. One application in progress per company and product. */
    @Transactional
    fun create(terms: ApplicationTerms): LoanApplication {
        val (claims, companyId) = CurrentCaller.requireClient()
        val product = product(terms.productCode)
        require(terms.purpose in product.purposes) { "Продукт «${product.name}» не подходит для этой цели" }
        if (applications.hasActive(companyId, product.code)) throw activeExists(product)
        val company = companies.find(companyId) ?: throw NotFoundException("Компания не найдена")
        val now = clock.instant()
        val saved = try {
            applications.save(LoanApplication(company = company, createdBy = claims.userId, terms = terms, createdAt = now, productVersion = product.version))
        } catch (exception: DataIntegrityViolationException) {
            // The same product requested twice at the same moment: the unique index decided.
            throw activeExists(product)
        }
        journal.append(saved.note(JournalNote.CREATED, actorOf(claims), now))
        return saved
    }

    @Transactional
    fun updateDraft(id: UUID, terms: ApplicationTerms): LoanApplication {
        val (_, application) = own(id)
        if (application.status == ApplicationStatus.DRAFT && application.activeAgentId != null) throw notFound()
        if (application.agentId != null) {
            throw ConflictException("Черновик агентской заявки может редактировать только агент")
        }
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
    fun submit(id: UUID, idempotencyKey: String?, acceptedProductVersion: Long? = null): LoanApplication {
        val (claims, application) = own(id)
        if (application.agentId != null) {
            throw ConflictException("Агентскую заявку нельзя отправить напрямую — сначала требуется подтверждение клиента")
        }
        if (idempotencyKey != null) {
            val previous = idempotencyKeys.find(claims.userId, idempotencyKey, SUBMIT)
            if (previous == id) return application
            if (previous != null) throw ConflictException("Этот Idempotency-Key уже использован для другой заявки")
        }
        // One statement sees a coherent catalogue; edits after it belong to later submissions.
        val catalog = products.all()
        val selected = catalog.firstOrNull { it.code == application.terms.productCode }
            ?: throw ConflictException("Продукт выключен из каталога — выберите другой продукт и создайте новую заявку")
        if ((acceptedProductVersion != null && acceptedProductVersion != selected.version) ||
            (acceptedProductVersion == null && application.productVersion != selected.version)
        ) {
            throw ConflictException("Условия продукта изменились — обновите заявку, проверьте новые условия и подтвердите их перед отправкой")
        }
        val pinned = application.copy(productVersion = selected.version, productCatalogSnapshot = catalog.associate { it.code to it.version })
        val now = clock.instant()
        val saved = commit(pinned.submit(selected, actorOf(claims), now))
        jobs.enqueue(saved.id, PipelineStep.DATA_CHECK, now)
        idempotencyKey?.let { idempotencyKeys.record(claims.userId, it, SUBMIT, saved.id) }
        return saved
    }

    /** T23: client reviews an agent-prepared application, grants bureau consent, and submits to the bank. */
    @Transactional
    fun confirmByClient(
        id: UUID,
        bureauConsent: Boolean,
        acceptedProductVersion: Long? = null,
        acceptedScenarioRevision: Long? = null,
    ): LoanApplication {
        val (claims, current) = own(id, forUpdate = true)
        val application = if (current.status == ApplicationStatus.DRAFT) {
            if (!canResumeIndependently(current)) throw ConflictException("Заявка ожидает повторной передачи агентом")
            scenarios.restoreHandoff(current).copy(clientManaged = true)
        } else {
            current
        }
        if (scenarios.confirm(application, acceptedScenarioRevision)) return current
        val catalog = products.all()
        val selected = catalog.firstOrNull { it.code == application.terms.productCode }
            ?: throw ConflictException("Продукт выключен из каталога — выберите другой продукт и создайте новую заявку")
        if ((acceptedScenarioRevision != null && selected.version != application.productVersion) ||
            (acceptedProductVersion != null && acceptedProductVersion != selected.version) ||
            (acceptedProductVersion == null && application.productVersion != selected.version)
        ) {
            throw ConflictException("Условия продукта изменились — обновите заявку, проверьте новые условия и подтвердите их перед отправкой")
        }
        val commissionRateBps = if (application.agentId != null) selected.agentCommissionRateBps else application.agentCommissionRateBps
        val commissionAmount = if (application.agentId != null && commissionRateBps != null) {
            application.terms.amount * commissionRateBps / 10_000L
        } else {
            application.agentCommissionAmount
        }
        val pinned = application.copy(
            productVersion = selected.version,
            productCatalogSnapshot = catalog.associate { it.code to it.version },
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
    fun returnToAgentByClient(id: UUID, comment: String?): LoanApplication {
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
    fun withdraw(id: UUID, comment: String?): LoanApplication {
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
    fun provideInfo(id: UUID, answer: String?): LoanApplication {
        val (claims, application) = own(id, forUpdate = true)
        val request = infoRequests.open(application.id) ?: throw ConflictException("Банк сейчас ничего не запрашивал")
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

    fun get(id: UUID): LoanApplication {
        val claims = CurrentCaller.claims()
        val application = applications.find(id)?.takeIf { claims.canSee(it) } ?: throw notFound()
        if (claims.isAgent) {
            if (!agentAccess.checkAccess(claims.userId, application.company.id).allowed || !agentEligibility.isEligible(application.company)) {
                throw notFound()
            }
        }
        return application
    }

    /** A client sees their company's applications; bank staff the queue without drafts. */
    fun list(statuses: Set<ApplicationStatus>?): List<LoanApplication> {
        val claims = CurrentCaller.claims()
        return when {
            claims.isClient -> {
                val (_, companyId) = CurrentCaller.requireClient()
                applications.listByCompany(companyId).filter { it.visibleToClient && (statuses.isNullOrEmpty() || it.status in statuses) }
            }
            else -> {
                CurrentCaller.requireBankStaff()
                applications.listForBank(statuses)
            }
        }
    }

    fun journal(id: UUID): List<JournalEntry> = journal.list(get(id).id)

    fun product(code: String): Product = products.find(code) ?: throw NotFoundException("Продукт не найден")

    fun product(application: LoanApplication, code: String = application.terms.productCode): Product = products.forApplication(application, code)

    fun currentProduct(code: String): Product? = products.latest(code)

    /** The application of the caller's company, for changing it: clients only (hiding private agent drafts). */
    fun own(id: UUID, forUpdate: Boolean = false): Pair<TokenClaims, LoanApplication> {
        val (claims, companyId) = CurrentCaller.requireClient()
        val current = if (forUpdate) applications.findForUpdate(id) else applications.find(id)
        val application = current?.takeIf { it.company.id == companyId && it.visibleToClient } ?: throw notFound()
        return claims to application
    }

    fun canResumeIndependently(application: LoanApplication): Boolean {
        val agentId = application.activeAgentId ?: return false
        return application.status == ApplicationStatus.DRAFT &&
            application.clientHandoffAt != null &&
            !agentAccess.checkAccess(agentId, application.company.id).allowed
    }

    fun clientReadView(application: LoanApplication): LoanApplication =
        if (application.status == ApplicationStatus.DRAFT && application.clientHandoffAt != null) {
            scenarios.restoreHandoff(application)
        } else {
            application
        }

    fun commit(transition: Transition): LoanApplication = transitions.commit(transition)

    private fun actorOf(claims: TokenClaims) = claims.toActor() ?: throw ConflictException("Это действие недоступно для вашей роли")

    private fun activeExists(product: Product) =
        ConflictException("По продукту «${product.name}» у компании уже есть заявка в работе — продолжите её или отзовите")

    private companion object {
        const val SUBMIT = "SUBMIT"
        const val MAX_COMMENT = 2000
    }
}
