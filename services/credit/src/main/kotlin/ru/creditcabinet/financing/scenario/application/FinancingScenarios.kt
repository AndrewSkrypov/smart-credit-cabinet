package ru.creditcabinet.financing.scenario.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.application.application.CreditFailure.Conflict
import ru.creditcabinet.application.application.CreditFailure.InvalidInput
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.document.domain.DocumentContext
import ru.creditcabinet.document.domain.DocumentRepository
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.financing.invoice.application.InvoiceTextReader
import ru.creditcabinet.financing.invoice.domain.InvoiceEvidence
import ru.creditcabinet.financing.scenario.domain.FinancingScenarioCalculation
import ru.creditcabinet.financing.scenario.domain.FinancingScenarioStatus
import ru.creditcabinet.financing.scenario.domain.ScenarioInputs
import ru.creditcabinet.financing.scenario.domain.ScenarioSnapshot
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductCatalog
import java.util.UUID

@Service
class FinancingScenarios(
    private val products: ProductCatalog,
    private val documents: DocumentRepository,
    private val files: FileStore,
    private val store: FinancingScenarioStore,
    private val invoiceText: InvoiceTextReader,
    private val content: ru.creditcabinet.document.domain.DocumentContent? = null,
) {
    fun forAgent(application: LoanApplication): ScenarioSnapshot? {
        if (application.status == ApplicationStatus.DRAFT) {
            return store.latest(application.id)?.snapshot?.copy(status = FinancingScenarioStatus.DRAFT)
        }
        return handed(application.id) ?: store.latest(application.id)?.snapshot
    }

    internal fun previewSavedDraft(
        application: LoanApplication,
        delayDays: Int,
        alternativeTermMonths: Int,
    ): Pair<Product, ScenarioSnapshot> {
        if (application.status != ApplicationStatus.DRAFT || application.version == null) throw SavedPreviewNotApplicable()
        val latest = store.latest(application.id) ?: throw SavedPreviewNotReady()
        val saved = latest.snapshot
        // forDraft() can persist catalog revisions in REMOTE mode; preview only reads.
        val product = products.find(application.terms.productCode) ?: throw SavedPreviewNotApplicable()
        val terms = application.terms
        if (saved.status != FinancingScenarioStatus.DRAFT ||
            saved.options.firstOrNull()?.termMonths != terms.termMonths ||
            saved.loanAmount != terms.amount ||
            latest.productVersion != product.version ||
            application.productVersion != product.version ||
            !product.active ||
            saved.annualRatePercent.compareTo(product.baseRate) != 0 ||
            !product.amountFits(terms.amount) ||
            !product.termFits(terms.termMonths)
        ) {
            throw SavedPreviewNotApplicable()
        }
        if (delayDays !in 0..365 || alternativeTermMonths == terms.termMonths || !product.termFits(alternativeTermMonths)) {
            throw InvalidInput("Недопустимая задержка или срок")
        }
        val inputs = saved.inputs.copy(
            delayDays = delayDays,
            alternativeTermMonths = alternativeTermMonths,
            selectedTermMonths = terms.termMonths,
            source = null,
        )
        return product to FinancingScenarioCalculation.draft(saved.revision, inputs, terms, product)
    }

    fun handed(applicationId: UUID): ScenarioSnapshot? {
        val history = store.recent(applicationId, handedOnly = true, limit = 2)
        val latest = history.firstOrNull() ?: return null
        return latest.snapshot.copy(changes = history.getOrNull(1)?.let(latest::changesSince).orEmpty())
    }

    internal fun hasHandedPacket(applicationId: UUID): Boolean = store.latest(applicationId, handedOnly = true) != null

    internal fun savedRevision(applicationId: UUID): Long? = store.latest(applicationId)?.snapshot?.revision

    internal fun preview(
        application: LoanApplication,
        delayDays: Int,
        alternativeTermMonths: Int,
        form: FinancingPreviewForm?,
    ): FinancingPreview {
        require(delayDays in 0..365) { "Укажите задержку от 0 до 365 дней" }
        val product = draftProduct(application)
        val saved = store.latest(application.id)
        val terms = form?.let { application.terms.copy(amount = it.amount, termMonths = it.baseTermMonths) } ?: application.terms
        val inputs = form?.inputs ?: savedInputs(saved, application, product)
        val previewInputs = inputs.copy(
            delayDays = delayDays,
            alternativeTermMonths = alternativeTermMonths,
            selectedTermMonths = terms.termMonths,
            source = null,
        )
        return FinancingPreview(
            application.copy(terms = terms),
            product,
            saved?.snapshot?.revision,
            previewInputs,
            FinancingScenarioCalculation.calculate(previewInputs, terms, product),
        )
    }

    /** Caller locks and authorizes the application in the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun saveDraft(application: LoanApplication, inputs: ScenarioInputs): ScenarioSnapshot {
        val product = draftProduct(application)
        val revision = (store.latest(application.id)?.snapshot?.revision ?: 0L) + 1
        val calculation = FinancingScenarioCalculation.calculate(inputs, application.terms, product)
        val snapshot = ScenarioSnapshot(
            revision,
            FinancingScenarioStatus.DRAFT,
            inputs,
            application.terms.amount,
            product.baseRate,
            calculation.options,
        )
        verifyInvoiceSource(application.id, inputs)
        store.append(application.id, ScenarioRevision(product.version, snapshot))
        return snapshot
    }

    private fun draftProduct(application: LoanApplication): Product {
        if (application.status != ApplicationStatus.DRAFT) {
            throw Conflict("Сценарий можно изменить только в черновике")
        }
        return products.find(application.terms.productCode)?.takeIf { it.active }
            ?: throw Conflict("Продукт заявки выключен из каталога")
    }

    private fun savedInputs(saved: ScenarioRevision?, application: LoanApplication, product: Product): ScenarioInputs {
        if (saved == null) throw Conflict("Сначала сохраните исходные данные в «Примерке кредита»")
        val snapshot = saved.snapshot
        if (saved.productVersion != product.version ||
            snapshot.loanAmount != application.terms.amount ||
            snapshot.options.first().termMonths != application.terms.termMonths
        ) {
            throw staleScenario()
        }
        return snapshot.inputs
    }

    /** Caller holds the application lock and commits the handoff in the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun prepareHandoff(application: LoanApplication, product: Product): LoanApplication {
        val latest = store.latest(application.id) ?: return application
        val snapshot = latest.snapshot
        val selectedTerm = snapshot.inputs.selectedTermMonths
        val sameTerm = application.terms.termMonths == snapshot.options.first().termMonths ||
            (snapshot.status == FinancingScenarioStatus.AWAITING_CLIENT && application.terms.termMonths == selectedTerm)
        if (application.status != ApplicationStatus.DRAFT ||
            snapshot.status == FinancingScenarioStatus.CONFIRMED ||
            !sameTerm ||
            application.terms.amount != snapshot.loanAmount ||
            product.version != latest.productVersion
        ) {
            throw Conflict("Условия заявки изменились — пересчитайте сценарий перед передачей")
        }
        // A new handoff after a return can carry new document versions, even when the terms were kept.
        snapshot.inputs.source?.documentId?.let { requireInvoiceDocument(application.id, it) }
        val revision = if (snapshot.status == FinancingScenarioStatus.DRAFT) {
            snapshot.revision
        } else {
            (snapshot.revision + 1).also { next ->
                store.append(application.id, latest.copy(snapshot = snapshot.copy(revision = next, status = FinancingScenarioStatus.DRAFT)))
            }
        }
        val documentIds = documents.list(application.id)
            .filter { it.context == DocumentContext.BUSINESS && !it.privateToBank }
            .map { it.id }
        val preparedTerms = application.terms.copy(termMonths = selectedTerm, bureauConsent = false)
        store.publish(application.id, revision, FinancingScenarioStatus.AWAITING_CLIENT, documentIds, preparedTerms)
        return application.copy(terms = preparedTerms)
    }

    /** Restores the packet's terms when the client continues a returned agent draft. */
    fun restoreHandoff(application: LoanApplication): LoanApplication {
        val handed = store.latest(application.id, handedOnly = true) ?: return application
        return application.copy(
            terms = checkNotNull(handed.handedTerms),
            productVersion = handed.productVersion,
        )
    }

    /** Returns true for an exact replay of a submission which is already committed. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun confirm(application: LoanApplication, acceptedScenarioRevision: Long?): Boolean {
        val revision = store.latest(application.id, handedOnly = true)
        if (revision == null) {
            if (acceptedScenarioRevision != null) throw staleScenario()
            return false
        }
        val snapshot = revision.snapshot
        if (acceptedScenarioRevision != snapshot.revision) throw staleScenario()
        if (snapshot.status == FinancingScenarioStatus.CONFIRMED && application.submittedAt != null) return true
        val canConfirm = application.status == ApplicationStatus.PENDING_CLIENT_CONFIRMATION ||
            (application.status == ApplicationStatus.DRAFT && application.clientManaged)
        if (!canConfirm ||
            snapshot.status != FinancingScenarioStatus.AWAITING_CLIENT ||
            application.terms != revision.handedTerms ||
            application.productVersion != revision.productVersion
        ) {
            throw staleScenario()
        }
        store.publish(application.id, snapshot.revision, FinancingScenarioStatus.CONFIRMED)
        return false
    }

    private fun verifyInvoiceSource(applicationId: UUID, inputs: ScenarioInputs) {
        val source = inputs.source ?: return
        val documentId = source.documentId ?: return
        val document = requireInvoiceDocument(applicationId, documentId)
        val file = (if (content == null) files.get(document.storedName, document.storageBackend) else content.open(document))
            ?: throw Conflict("Счёт недоступен — приложите его заново")
        val bytes = file.content.use { it.readNBytes(InvoiceTextReader.MAX_FILE_BYTES.toInt() + 1) }
        require(bytes.size <= InvoiceTextReader.MAX_FILE_BYTES) { "Счёт превышает допустимый размер" }
        val evidence = InvoiceEvidence.from(invoiceText.read(bytes))
        val amountMatches = evidence.amounts.any {
            it.page == source.page && it.quote == source.amountQuote && it.value == inputs.expectedReceiptAmount
        }
        val dateMatches = evidence.dates.any {
            it.fact.page == source.page && it.fact.quote == source.dateQuote && it.fact.value == inputs.expectedReceiptDate
        }
        require(amountMatches && dateMatches) { "Сумма или дата не совпадает со строкой приложенного счёта" }
    }

    private fun requireInvoiceDocument(applicationId: UUID, documentId: UUID) =
        documents.find(applicationId, documentId)?.takeIf {
            it.context == DocumentContext.BUSINESS &&
                !it.privateToBank &&
                it.sizeBytes in 1..InvoiceTextReader.MAX_FILE_BYTES
        } ?: throw Conflict("Счёт должен быть приложен к этой заявке")

    private fun staleScenario() = Conflict("Пакет финансирования изменился — откройте переданную версию и подтвердите её")
}

internal class SavedPreviewNotReady : RuntimeException()

internal class SavedPreviewNotApplicable : RuntimeException()
