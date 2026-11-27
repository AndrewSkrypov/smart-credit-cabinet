package ru.creditcabinet.application.application

import org.springframework.stereotype.Service
import ru.creditcabinet.application.domain.ActorType
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.InfoRequest
import ru.creditcabinet.application.domain.InfoRequests
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.assessment.domain.Decision
import ru.creditcabinet.assessment.domain.DecisionRepository
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.offer.Loan
import ru.creditcabinet.offer.Loans
import ru.creditcabinet.offer.Offer
import ru.creditcabinet.offer.OfferKind
import ru.creditcabinet.offer.OfferRepository
import ru.creditcabinet.offer.SigningSessionRecord
import ru.creditcabinet.offer.SigningSessions
import ru.creditcabinet.product.domain.Calculation
import ru.creditcabinet.product.domain.LoanMath
import ru.creditcabinet.product.domain.ProductCatalog
import ru.creditcabinet.product.domain.forApplication

data class ApplicationOffer(val offer: Offer, val productName: String, val changes: List<String>)

data class ApplicationProcess(
    val infoRequest: InfoRequest?,
    val offer: ApplicationOffer?,
    val signing: SigningSessionRecord?,
    val loan: Loan?,
    val rejection: Decision?,
)

interface ApplicationProcesses {
    fun read(authorizedApplication: LoanApplication): ApplicationProcess

    fun schedule(authorizedApplication: LoanApplication): Calculation
}

@Service
class ReadApplicationProcess(
    private val products: ProductCatalog,
    private val offers: OfferRepository,
    private val infoRequests: InfoRequests,
    private val decisions: DecisionRepository,
    private val signing: SigningSessions,
    private val loans: Loans,
) : ApplicationProcesses {
    override fun schedule(authorizedApplication: LoanApplication): Calculation {
        val loan = loans.find(authorizedApplication.id)
        val offer = offers.current(authorizedApplication.id) ?: throw NotFoundException("Графика пока нет: банк ещё не сделал предложение")
        val product = products.forApplication(authorizedApplication, offer.productCode)
        return LoanMath.calculate(loan?.amount ?: offer.amount, loan?.rate ?: offer.rate, loan?.termMonths ?: offer.termMonths, product.revolving)
    }

    override fun read(authorizedApplication: LoanApplication): ApplicationProcess {
        val id = authorizedApplication.id
        val offer = offers.current(id)
        val offerDecision = offer?.let { decisions.find(it.decisionId) }
        val latest = decisions.latest(id)
        val currentOffer = offer?.let {
            val changes = when {
                latest?.decidedByType != ActorType.SYSTEM -> listOfNotNull(latest?.comment)
                offer.kind != OfferKind.COUNTER || offerDecision == null -> emptyList()
                offerDecision.decidedByType == ActorType.SYSTEM -> offerDecision.recommendation?.notes.orEmpty()
                else -> listOfNotNull(offerDecision.comment)
            }
            ApplicationOffer(it, products.forApplication(authorizedApplication, it.productCode).name, changes)
        }
        return ApplicationProcess(
            infoRequests.open(id),
            currentOffer,
            signing.latest(id),
            loans.find(id),
            latest?.takeIf { authorizedApplication.status == ApplicationStatus.REJECTED },
        )
    }
}
