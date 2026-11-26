package ru.creditcabinet.financing.scenario

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.document.domain.DocumentContext
import ru.creditcabinet.document.domain.DocumentRepository
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.financing.invoice.InvoiceEvidence
import ru.creditcabinet.financing.invoice.InvoicePdfReader
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductCatalog
import java.util.UUID

@Service
class FinancingScenarios(
    private val products: ProductCatalog,
    private val documents: DocumentRepository,
    private val files: FileStore,
    private val store: FinancingScenarioStore,
) {
    fun forAgent(application: LoanApplication): FinancingScenarioSnapshot? {
        if (application.status == ApplicationStatus.DRAFT) {
            return store.latest(application.id)?.snapshot?.copy(status = FinancingScenarioStatus.DRAFT)
        }
        return handed(application.id) ?: store.latest(application.id)?.snapshot
    }

    fun handed(applicationId: UUID): FinancingScenarioSnapshot? {
        val history = store.recent(applicationId, handedOnly = true, limit = 2)
        val latest = history.firstOrNull() ?: return null
        return latest.snapshot.copy(changes = history.getOrNull(1)?.let(latest::changesSince).orEmpty())
    }

    internal fun hasHandedPacket(applicationId: UUID): Boolean = store.latest(applicationId, handedOnly = true) != null

    /** Caller locks and authorizes the application in the same transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun saveDraft(application: LoanApplication, inputs: FinancingScenarioInputs): FinancingScenarioSnapshot {
        if (application.status != ApplicationStatus.DRAFT) {
            throw ConflictException("Сценарий можно изменить только в черновике")
        }
        val product = products.find(application.terms.productCode)
            ?: throw ConflictException("Продукт заявки выключен из каталога")
        val revision = (store.latest(application.id)?.snapshot?.revision ?: 0L) + 1
        val snapshot = FinancingScenarioCalculation.draft(revision, inputs, application.terms, product)
        verifyInvoiceSource(application.id, inputs)
        store.append(application.id, ScenarioRevision(product.version, snapshot))
        return snapshot
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
            throw ConflictException("Условия заявки изменились — пересчитайте сценарий перед передачей")
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

    private fun verifyInvoiceSource(applicationId: UUID, inputs: FinancingScenarioInputs) {
        val source = inputs.source ?: return
        val documentId = source.documentId ?: return
        val document = requireInvoiceDocument(applicationId, documentId)
        val file = files.get(document.storedName, document.storageBackend)
            ?: throw ConflictException("Счёт недоступен — приложите его заново")
        val bytes = file.content.use { it.readNBytes(InvoicePdfReader.MAX_FILE_BYTES.toInt() + 1) }
        require(bytes.size <= InvoicePdfReader.MAX_FILE_BYTES) { "Счёт превышает допустимый размер" }
        val evidence = InvoiceEvidence.from(InvoicePdfReader().read(bytes))
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
                it.sizeBytes in 1..InvoicePdfReader.MAX_FILE_BYTES
        } ?: throw ConflictException("Счёт должен быть приложен к этой заявке")

    private fun staleScenario() = ConflictException("Пакет финансирования изменился — откройте переданную версию и подтвердите её")
}
