package ru.creditcabinet.integration

import ru.creditcabinet.assessment.domain.BankInfo
import ru.creditcabinet.assessment.domain.BureauInfo
import ru.creditcabinet.assessment.domain.RegistryInfo
import ru.creditcabinet.assessment.domain.SourceKind
import ru.creditcabinet.assessment.domain.TaxInfo
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** A source did not answer in time, answered with an error, or its circuit is open. The step is retried later. */
class SourceUnavailableException(val source: String, message: String) : RuntimeException(message)

/** The systems the pipeline asks about a company. */
interface CompanySources {
    fun registry(inn: String): RegistryInfo
    fun tax(inn: String): TaxInfo

    /** Only with the client's consent on record; [consentId] identifies it for the bureau. */
    fun bureau(inn: String, consentId: String): BureauInfo
    fun bank(inn: String): BankInfo

    fun fetch(kind: SourceKind, inn: String, consentId: String): Any =
        when (kind) {
            SourceKind.REGISTRY -> registry(inn)
            SourceKind.TAX -> tax(inn)
            SourceKind.BUREAU -> bureau(inn, consentId)
            SourceKind.BANK -> bank(inn)
        }
}

data class SigningStarted(val sessionId: UUID, val code: String, val expiresAt: Instant)

sealed interface SigningResult {
    data class Signed(val signatureId: UUID, val signedAt: Instant) : SigningResult

    /** The code was wrong or expired: [message] says what to do. */
    data class Refused(val message: String) : SigningResult
}

/** The signing service of the bank (simple electronic signature with a one-time code). */
interface SigningGateway {
    fun start(documentId: String, title: String, text: String, signerName: String): SigningStarted
    fun confirm(sessionId: UUID, code: String): SigningResult
}

data class LoanRequest(
    val applicationId: UUID,
    val companyInn: String,
    val amount: Long,
    val termMonths: Int,
    val annualRate: BigDecimal,
    val revolving: Boolean,
    val signatureId: UUID,
)

data class LoanBooked(
    val contractNumber: String,
    val loanAccount: String,
    val settlementAccount: String,
    val disbursedAt: Instant,
    val maturityDate: LocalDate,
)

/** The core banking system: opens the loan and pays the money out. Idempotent per application. */
interface CoreBanking {
    fun disburse(request: LoanRequest): LoanBooked
}
