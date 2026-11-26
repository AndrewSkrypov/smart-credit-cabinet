package ru.creditcabinet.application.application

import org.springframework.stereotype.Service
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
)

interface ApplicationDetails {
    fun product(authorizedApplication: LoanApplication): Product

    fun read(authorizedApplication: LoanApplication, caller: TokenClaims): ApplicationDetail
}

@Service
class ReadApplicationDetails(
    private val products: ProductCatalog,
    private val documents: ApplicationDocuments,
    private val processes: ApplicationProcesses,
) : ApplicationDetails {
    override fun product(authorizedApplication: LoanApplication): Product = products.forApplication(authorizedApplication)

    override fun read(authorizedApplication: LoanApplication, caller: TokenClaims): ApplicationDetail {
        val application = authorizedApplication
        val product = product(application)
        val terms = application.terms
        val uploaded = documents.list(application)
        val isDraft = application.status == ApplicationStatus.DRAFT
        val isOwnClient = caller.isClient && caller.companyId == application.company.id
        val isOwnAgent = caller.isAgent && application.agentId == caller.userId
        val actions = caller.toActor()?.let { actor ->
            ApplicationStateMachine.eventsFrom(application.status, actor.type)
                // Signing belongs to the company representative with signing rights.
                .filter { it != ApplicationEvent.SIGN || caller.canSign }
                // A client confirms an agent draft through a separate transition.
                .filter { it != ApplicationEvent.SUBMIT || application.agentId == null }
        }.orEmpty()
        val currentProduct = if (isDraft || application.status == ApplicationStatus.PENDING_CLIENT_CONFIRMATION) {
            products.latest(product.code)
        } else {
            null
        }
        val submissionProblems = when {
            !isDraft -> emptyList()
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
            product = product,
            currentProduct = currentProduct,
            allowedActions = actions,
            editable = ((isOwnClient && application.agentId == null) || isOwnAgent) && isDraft,
            canUploadDocuments = (isOwnClient || isOwnAgent) && (isDraft || application.status == ApplicationStatus.INFO_REQUESTED),
            submissionProblems = submissionProblems,
            requiredDocuments = DocumentRequirements.required(product, terms.amount, application.company.legalForm),
            documents = uploaded,
            estimate = estimate,
            process = processes.read(application),
            showSigningCode = isOwnClient && caller.canSign,
        )
    }
}
