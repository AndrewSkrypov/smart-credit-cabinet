package ru.creditcabinet.preapproved.domain

import ru.creditcabinet.application.domain.ApplicationTerms
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class PreApprovedOfferStatus { ACTIVE, USED, EXPIRED }

data class PreApprovedOffer(
    val id: UUID,
    val companyId: UUID,
    val productCode: String,
    val maxAmount: Long,
    val minTermMonths: Int,
    val maxTermMonths: Int,
    val rate: BigDecimal,
    val validUntil: Instant,
    val status: PreApprovedOfferStatus,
    val applicationId: UUID? = null,
    val createdAt: Instant,
    val updatedAt: Instant = createdAt,
) {
    fun isExpired(now: Instant): Boolean = !validUntil.isAfter(now)

    fun isAvailable(now: Instant): Boolean = status == PreApprovedOfferStatus.ACTIVE && !isExpired(now)

    fun isWithinBounds(amount: Long, termMonths: Int): Boolean =
        amount in 1..maxAmount && termMonths in minTermMonths..maxTermMonths

    fun isWithinBounds(terms: ApplicationTerms): Boolean =
        terms.productCode == productCode && isWithinBounds(terms.amount, terms.termMonths)

    fun isValidForScoring(applicationId: UUID, terms: ApplicationTerms, now: Instant): Boolean =
        status == PreApprovedOfferStatus.USED &&
            this.applicationId == applicationId &&
            !isExpired(now) &&
            isWithinBounds(terms)
}

interface PreApprovedOfferStore {
    fun find(id: UUID): PreApprovedOffer?

    fun listActiveByCompany(companyId: UUID, now: Instant): List<PreApprovedOffer>

    fun listActiveByCompanies(companyIds: Collection<UUID>, now: Instant): List<PreApprovedOffer>

    fun markUsed(id: UUID, applicationId: UUID, now: Instant): Boolean

    fun release(id: UUID, applicationId: UUID, now: Instant): Boolean
}
