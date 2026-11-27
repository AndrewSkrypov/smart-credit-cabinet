package ru.creditcabinet.application.application

import org.springframework.stereotype.Service
import ru.creditcabinet.agent.domain.AgentAccessUnavailable
import ru.creditcabinet.application.domain.ApplicationEvent
import ru.creditcabinet.application.domain.ApplicationStateMachine
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.service.toActor
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.document.application.ApplicationDocuments
import ru.creditcabinet.document.domain.ApplicationDocument
import ru.creditcabinet.document.domain.DocumentRequirement
import ru.creditcabinet.document.domain.DocumentRequirements
import ru.creditcabinet.product.domain.Calculation
import ru.creditcabinet.product.domain.LoanMath
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductCatalog
import ru.creditcabinet.product.domain.forApplication

data class ApplicationDetail(
    val application: LoanApplication,
    val product: Product,
    val currentProduct: Product?,
    val allowedActions: List<ApplicationEvent>,
    val editable: Boolean,
    val canUploadDocuments: Boolean,
    val submissionProblems: List<String>,
    val requiredDocuments: List<DocumentRequirement>,
    val documents: List<ApplicationDocument>,
    val estimate: Calculation?,
    val process: ApplicationProcess,
    val showSigningCode: Boolean,
    val canResumeIndependently: Boolean,
)

interface ApplicationDetails {
    fun product(authorizedApplication: LoanApplication): Product

    /** The application as this caller may see it: a client sees a handed-back agent draft in its handoff state. */
    fun readView(authorizedApplication: LoanApplication, caller: TokenClaims): LoanApplication

    fun read(authorizedApplication: LoanApplication, caller: TokenClaims, newDraft: Boolean = false): ApplicationDetail
}

fun interface DraftProductPreview {
    fun latest(productCode: String): Result<Product?>
}

@Service
class ReadApplicationDetails(
    private val products: ProductCatalog,
    private val documents: ApplicationDocuments,
    private val processes: ApplicationProcesses,
    private val operations: ApplicationOperations,
    private val preview: DraftProductPreview,
) : ApplicationDetails {
    override fun product(authorizedApplication: LoanApplication): Product = products.forApplication(authorizedApplication)

    override fun readView(authorizedApplication: LoanApplication, caller: TokenClaims): LoanApplication =
        if (caller.isClient) operations.clientReadView(authorizedApplication) else authorizedApplication

    override fun read(authorizedApplication: LoanApplication, caller: TokenClaims, newDraft: Boolean): ApplicationDetail {
        val application = readView(authorizedApplication, caller)
        val product = product(application)
        val terms = application.terms
        val uploaded = if (newDraft) emptyList() else documents.list(application)
        val isDraft = application.status == ApplicationStatus.DRAFT
        val isOwnClient = caller.isClient && caller.companyId == application.company.id
        val isOwnAgent = caller.isAgent && application.activeAgentId == caller.userId
        val canResume = isOwnClient &&
            try {
                operations.canResumeIndependently(application)
            } catch (_: AgentAccessUnavailable) {
                false
            }
        val actions = caller.toActor()?.let { actor ->
            ApplicationStateMachine.eventsFrom(application.status, actor.type)
                // Signing belongs to the company representative with signing rights.
                .filter { it != ApplicationEvent.SIGN || caller.canSign }
                // A client confirms an agent draft through a separate transition.
                .filter { it != ApplicationEvent.SUBMIT || application.agentId == null }
                // A client takes over a handed-back draft only when the agent lost access.
                .filter { it != ApplicationEvent.CONFIRM_AND_SUBMIT || !isDraft || canResume }
                // An active agent draft is withdrawn by the agent, not by the client.
                .filter { it != ApplicationEvent.WITHDRAW || !isDraft || application.activeAgentId == null }
        }.orEmpty()
        // The catalog is a separate service: its outage must not hide the application card.
        val catalogPreview = if (isDraft || application.status == ApplicationStatus.PENDING_CLIENT_CONFIRMATION) {
            preview.latest(product.code)
        } else {
            Result.success(null)
        }
        val currentProduct = catalogPreview.getOrNull()
        val submissionProblems = when {
            !isDraft -> emptyList()
            catalogPreview.isFailure -> listOf("Каталог временно недоступен — попробуйте позже")
            currentProduct?.active != true -> listOf("Продукт выключен из каталога — выберите другой продукт")
            application.agentId != null -> application.agentSubmissionProblems(currentProduct)
            else -> application.submissionProblems(currentProduct)
        }
        val estimate = if (product.amountFits(terms.amount) && product.termFits(terms.termMonths)) {
            LoanMath.calculate(terms.amount, product.baseRate, terms.termMonths, product.revolving)
        } else {
            null
        }
        return ApplicationDetail(
            application = application,
            product = product,
            currentProduct = currentProduct,
            allowedActions = actions,
            editable = ((isOwnClient && application.agentId == null) || isOwnAgent) && isDraft,
            canUploadDocuments = when (application.status) {
                ApplicationStatus.DRAFT -> (isOwnClient && application.activeAgentId == null) || isOwnAgent
                ApplicationStatus.INFO_REQUESTED -> isOwnClient || isOwnAgent
                else -> false
            },
            submissionProblems = submissionProblems,
            requiredDocuments = DocumentRequirements.required(product, terms.amount, application.company.legalForm),
            documents = uploaded,
            estimate = estimate,
            process = processes.read(application),
            showSigningCode = isOwnClient && caller.canSign,
            canResumeIndependently = canResume,
        )
    }
}
