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
            applications.save(LoanApplication(company = company, createdBy = claims.userId, terms = terms, createdAt = now))
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
    fun submit(id: UUID, idempotencyKey: String?): LoanApplication {
        val (claims, application) = own(id)
        if (idempotencyKey != null) {
            val previous = idempotencyKeys.find(claims.userId, idempotencyKey, SUBMIT)
            if (previous == id) return application
            if (previous != null) throw ConflictException("Этот Idempotency-Key уже использован для другой заявки")
        }
        val now = clock.instant()
        val saved = commit(application.submit(product(application.terms.productCode), actorOf(claims), now))
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

    /** The application of the caller's company, for changing it: clients only. */
    fun own(id: UUID): Pair<TokenClaims, LoanApplication> {
        val (claims, companyId) = CurrentCaller.requireClient()
        val application = applications.find(id)?.takeIf { it.company.id == companyId } ?: throw notFound()
        return claims to application
    }

    /** Saves the moved application and its journal entry in the caller's transaction. */
    fun commit(transition: Transition): LoanApplication {
        val saved = applications.save(transition.application)
        journal.append(transition.entry)
        return saved
    }

    private fun actorOf(claims: TokenClaims) = claims.toActor() ?: throw ConflictException("Это действие недоступно для вашей роли")

    private fun activeExists(product: Product) =
        ConflictException("По продукту «${product.name}» у компании уже есть заявка в работе — продолжите её или отзовите")

    private companion object {
        const val SUBMIT = "SUBMIT"
        const val MAX_COMMENT = 2000
    }
}
