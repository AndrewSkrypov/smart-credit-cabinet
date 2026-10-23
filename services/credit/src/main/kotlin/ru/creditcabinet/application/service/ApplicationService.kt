package ru.creditcabinet.application.service

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
        val product = product(application.terms.productCode)
        require(terms.purpose in product.purposes) { "Продукт «${product.name}» не подходит для этой цели" }
        return applications.save(application.updateDraft(terms, clock.instant()))
    }

    /**
     * T2. With an Idempotency-Key a repeated request (a double click, a retry after a timeout)
     * returns the application as it is instead of a second attempt and its 409.
     */
    @Transactional
    fun submit(id: UUID, idempotencyKey: String?, acceptedProductVersion: Long? = null): LoanApplication {
        val (claims, application) = own(id)
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

    /** T21: the client stops the application; pending pipeline steps are cancelled with it. */
    @Transactional
    fun withdraw(id: UUID, comment: String?): LoanApplication {
        val (claims, application) = own(id)
        val saved = commit(application.move(ApplicationEvent.WITHDRAW, actorOf(claims), clock.instant(), comment = comment?.take(MAX_COMMENT)))
        jobs.cancelPending(saved.id)
        return saved
    }

    /**
     * T5: the client did what the bank asked. Every requested document must have been uploaded
     * after the request, and questions need an answer; then the completeness check runs again.
     */
    @Transactional
    fun provideInfo(id: UUID, answer: String?): LoanApplication {
        val (claims, application) = own(id)
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
        return applications.find(id)?.takeIf { claims.canSee(it) } ?: throw notFound()
    }

    /** A client sees their company's applications; bank staff the queue without drafts. */
    fun list(statuses: Set<ApplicationStatus>?): List<LoanApplication> {
        val claims = CurrentCaller.claims()
        return when {
            claims.isClient -> {
                val (_, companyId) = CurrentCaller.requireClient()
                applications.listByCompany(companyId).filter { statuses.isNullOrEmpty() || it.status in statuses }
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

    /** The application of the caller's company, for changing it: clients only. */
    fun own(id: UUID): Pair<TokenClaims, LoanApplication> {
        val (claims, companyId) = CurrentCaller.requireClient()
        val application = applications.find(id)?.takeIf { it.company.id == companyId } ?: throw notFound()
        return claims to application
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
