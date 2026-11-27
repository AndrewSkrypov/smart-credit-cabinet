package ru.creditcabinet.preapproved.application

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.application.domain.Actor
import ru.creditcabinet.application.domain.ActorType
import ru.creditcabinet.application.domain.ApplicationJournal
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.CommissionStatus
import ru.creditcabinet.application.domain.CompanyDirectory
import ru.creditcabinet.application.domain.JournalNote
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.service.toActor
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.preapproved.domain.PreApprovedOffer
import ru.creditcabinet.preapproved.domain.PreApprovedOfferStore
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductCatalog
import java.time.Clock
import java.util.UUID

@Service
class PreApprovedOfferService(
    private val store: PreApprovedOfferStore,
    private val applications: ApplicationRepository,
    private val journal: ApplicationJournal,
    private val companies: CompanyDirectory,
    private val products: ProductCatalog,
    private val clock: Clock,
) {
    fun listForCompany(companyId: UUID): List<PreApprovedOffer> =
        store.listActiveByCompany(companyId, clock.instant())

    fun listForCompanies(companyIds: Collection<UUID>): List<PreApprovedOffer> =
        store.listActiveByCompanies(companyIds, clock.instant())

    @Transactional
    fun applyForClient(offerId: UUID): LoanApplication {
        val (claims, companyId) = CurrentCaller.requireClient()
        val offer = store.find(offerId)?.takeIf { it.companyId == companyId }
            ?: throw NotFoundException("Предодобренное предложение не найдено")
        return createFromOffer(
            offer = offer,
            createdBy = claims.userId,
            actor = claims.toActor() ?: throw ConflictException("Это действие недоступно для вашей роли"),
            agentId = null,
            agentName = null,
        )
    }

    @Transactional
    fun applyForAgent(
        offerId: UUID,
        agentId: UUID,
        companyIds: Collection<UUID>,
        agentName: String? = null,
    ): LoanApplication {
        val offer = store.find(offerId)?.takeIf { it.companyId in companyIds }
            ?: throw NotFoundException("Предодобренное предложение не найдено")
        return createFromOffer(
            offer = offer,
            createdBy = agentId,
            actor = Actor(ActorType.AGENT, agentId),
            agentId = agentId,
            agentName = agentName,
        )
    }

    /**
     * If a draft's amount or term moves outside the pre-approved offer bounds, detach the offer
     * from the application and return the offer to ACTIVE so standard scoring applies.
     */
    @Transactional
    fun detachIfOutOfBounds(application: LoanApplication, terms: ApplicationTerms): LoanApplication {
        val offerId = application.preApprovedOfferId ?: return application
        val offer = store.find(offerId)
        if (offer == null || !offer.isWithinBounds(terms)) {
            store.release(offerId, application.id, clock.instant())
            return application.copy(preApprovedOfferId = null)
        }
        return application
    }

    /** Releases a pre-approved offer back to ACTIVE when an unsubmitted draft is cancelled or withdrawn. */
    @Transactional
    fun release(offerId: UUID, applicationId: UUID) {
        store.release(offerId, applicationId, clock.instant())
    }

    private fun createFromOffer(
        offer: PreApprovedOffer,
        createdBy: UUID,
        actor: Actor,
        agentId: UUID?,
        agentName: String?,
    ): LoanApplication {
        val now = clock.instant()
        if (!offer.isAvailable(now)) {
            throw ConflictException("Предодобренное предложение уже использовано или истекло")
        }
        val product = products.find(offer.productCode)
            ?: throw ConflictException("Продукт по предложению недоступен")
        val amount = minOf(offer.maxAmount, product.maxAmount)
        val term = minOf(offer.maxTermMonths, product.maxTermMonths)
        if (amount < product.minAmount || term < maxOf(offer.minTermMonths, product.minTermMonths)) {
            throw ConflictException("Предодобренное предложение не подходит под текущие условия продукта")
        }
        if (applications.hasActive(offer.companyId, product.code)) {
            throw activeExists(product)
        }
        val company = companies.find(offer.companyId) ?: throw NotFoundException("Компания не найдена")
        val defaultPurpose = product.purposes.minBy { it.ordinal }
        val terms = ApplicationTerms(
            productCode = product.code,
            purpose = defaultPurpose,
            amount = amount,
            termMonths = term,
        )
        val commissionRateBps = if (agentId != null) product.agentCommissionRateBps else null
        val commissionAmount = commissionRateBps?.let { amount * it / 10_000L }
        val commissionStatus = if (agentId != null) CommissionStatus.EXPECTED else CommissionStatus.NONE
        val draft = LoanApplication(
            company = company,
            createdBy = createdBy,
            terms = terms,
            createdAt = now,
            productVersion = product.version,
            agentId = agentId,
            agentName = agentName,
            preApprovedOfferId = offer.id,
            agentCommissionRateBps = commissionRateBps,
            agentCommissionAmount = commissionAmount,
            agentCommissionStatus = commissionStatus,
        )
        val saved = try {
            applications.save(draft)
        } catch (exception: DataIntegrityViolationException) {
            throw activeExists(product)
        }
        if (!store.markUsed(offer.id, saved.id, now)) {
            throw ConflictException("Предодобренное предложение уже использовано или истекло")
        }
        journal.append(saved.note(JournalNote.CREATED, actor, now, mapOf("preApprovedOfferId" to offer.id.toString())))
        return saved
    }

    private fun activeExists(product: Product) =
        ConflictException("По продукту «${product.name}» у компании уже есть заявка в работе — продолжите её или отзовите")
}
