package ru.creditcabinet.preapproved.domain

import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.product.domain.Product
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
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

    companion object {
        private const val MIN_ANNUAL_REVENUE = 3_000_000L
        private const val LIMIT_STEP = 100_000L
        private val RATE_DISCOUNT = BigDecimal("2.00")
        private val MIN_RATE = BigDecimal("10.00")

        /**
         * Evaluates whether a company qualifies for a pre-approved offer based on annual turnover
         * and credit bureau delinquency history. Returns null if ineligible.
         */
        fun evaluate(
            companyId: UUID,
            product: Product,
            annualRevenue: Long,
            bureauWorstOverdueDays: Int,
            existingMonthlyPayments: Long,
            now: Instant,
            validDays: Long = 30L,
        ): PreApprovedOffer? {
            val withinWindow = (product.effectiveFrom == null || !now.isBefore(product.effectiveFrom)) &&
                (product.effectiveTo == null || !now.isAfter(product.effectiveTo))
            if (!product.active || !withinWindow) return null
            if (bureauWorstOverdueDays > 0 || annualRevenue < MIN_ANNUAL_REVENUE) return null
            val rawCapacity = (annualRevenue * 20L / 100L) - existingMonthlyPayments * 12L
            val capped = rawCapacity.coerceAtMost(product.autoApprovalLimit.coerceAtMost(product.maxAmount))
            val rounded = (capped / LIMIT_STEP) * LIMIT_STEP
            if (rounded < product.minAmount) return null
            val discountedRate = product.baseRate.subtract(RATE_DISCOUNT).coerceAtLeast(MIN_RATE).setScale(2, RoundingMode.HALF_UP)
            val maxTerm = product.maxTermMonths.coerceAtMost(24).coerceAtLeast(product.minTermMonths)
            return PreApprovedOffer(
                id = UUID.randomUUID(),
                companyId = companyId,
                productCode = product.code,
                maxAmount = rounded,
                minTermMonths = product.minTermMonths,
                maxTermMonths = maxTerm,
                rate = discountedRate,
                validUntil = now.plus(validDays, ChronoUnit.DAYS),
                status = PreApprovedOfferStatus.ACTIVE,
                applicationId = null,
                createdAt = now,
                updatedAt = now,
            )
        }
    }
}

interface PreApprovedOfferStore {
    fun find(id: UUID): PreApprovedOffer?

    fun create(offer: PreApprovedOffer)

    fun listActiveByCompany(companyId: UUID, now: Instant): List<PreApprovedOffer>

    fun listActiveByCompanies(companyIds: Collection<UUID>, now: Instant): List<PreApprovedOffer>

    fun markUsed(id: UUID, applicationId: UUID, now: Instant): Boolean

    fun release(id: UUID, applicationId: UUID, now: Instant): Boolean
}
