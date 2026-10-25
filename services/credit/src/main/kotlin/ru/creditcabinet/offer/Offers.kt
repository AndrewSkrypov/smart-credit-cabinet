package ru.creditcabinet.offer

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class OfferKind { APPROVED, COUNTER }

enum class OfferStatus { ISSUED, ACCEPTED, DECLINED, EXPIRED, WITHDRAWN }

data class Offer(
    val id: UUID = UUID.randomUUID(),
    val applicationId: UUID,
    val decisionId: UUID,
    val kind: OfferKind,
    val productCode: String,
    val amount: Long,
    val termMonths: Int,
    val rate: BigDecimal,
    val monthlyPayment: BigDecimal,
    val totalPayment: BigDecimal,
    val validUntil: Instant,
    val status: OfferStatus = OfferStatus.ISSUED,
    val createdAt: Instant,
    val decidedAt: Instant? = null,
    val productVersion: Long = 1,
)

data class SigningSessionRecord(
    val id: UUID = UUID.randomUUID(),
    val applicationId: UUID,
    val offerId: UUID,
    val externalSessionId: UUID,
    val code: String,
    val expiresAt: Instant,
    val signatureId: UUID? = null,
    val signedAt: Instant? = null,
    val signedBy: UUID? = null,
    val createdAt: Instant,
    val contractSnapshotId: UUID? = null,
)

data class Loan(
    val applicationId: UUID,
    val contractNumber: String,
    val loanAccount: String,
    val settlementAccount: String,
    val amount: Long,
    val termMonths: Int,
    val rate: BigDecimal,
    val revolving: Boolean,
    val disbursedAt: Instant,
    val maturityDate: LocalDate,
)

interface OfferRepository {
    fun save(offer: Offer)
    fun current(applicationId: UUID): Offer?
    fun updateStatus(id: UUID, status: OfferStatus, at: Instant)
}

interface SigningSessions {
    fun save(session: SigningSessionRecord)
    fun latest(applicationId: UUID): SigningSessionRecord?
    fun markSigned(id: UUID, signatureId: UUID, signedAt: Instant, signedBy: UUID)
}

interface Loans {
    fun save(loan: Loan)
    fun find(applicationId: UUID): Loan?
}
