package ru.creditcabinet.application.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.hibernate.annotations.Generated
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.ApplicationTerms
import ru.creditcabinet.application.domain.CloseReason
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.product.domain.FinancingPurpose
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "applications")
class ApplicationEntity(
    @Id
    val id: UUID,
    // Taken from a database sequence on insert.
    @Generated
    @Column(insertable = false, updatable = false)
    val number: Long?,
    @Column(name = "company_id", nullable = false)
    val companyId: UUID,
    @Column(name = "company_inn", nullable = false)
    val companyInn: String,
    @Column(name = "company_name")
    val companyName: String?,
    @Enumerated(EnumType.STRING)
    @Column(name = "legal_form", nullable = false)
    val legalForm: LegalForm,
    @Column(name = "created_by", nullable = false)
    val createdBy: UUID,
    @Column(name = "product_code", nullable = false)
    val productCode: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val purpose: FinancingPurpose,
    @Column(name = "purpose_comment")
    val purposeComment: String?,
    @Column(nullable = false)
    val amount: Long,
    @Column(name = "term_months", nullable = false)
    val termMonths: Int,
    @Column(name = "declared_revenue_12m")
    val declaredRevenue12m: Long?,
    @Column(name = "declared_monthly_debt_payments")
    val declaredMonthlyDebtPayments: Long?,
    @Column(name = "bureau_consent", nullable = false)
    val bureauConsent: Boolean,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    val status: ApplicationStatus,
    @Enumerated(EnumType.STRING)
    @Column(name = "close_reason")
    val closeReason: CloseReason?,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant,
    @Column(name = "submitted_at")
    val submittedAt: Instant?,
    @Column(name = "status_changed_at", nullable = false)
    val statusChangedAt: Instant,
    @Version
    val version: Long?,
) {
    fun toDomain() =
        LoanApplication(
            id = id,
            number = number,
            company = CompanySnapshot(companyId, companyInn, companyName, legalForm),
            createdBy = createdBy,
            terms = ApplicationTerms(
                productCode = productCode,
                purpose = purpose,
                purposeComment = purposeComment,
                amount = amount,
                termMonths = termMonths,
                declaredRevenue12m = declaredRevenue12m,
                declaredMonthlyDebtPayments = declaredMonthlyDebtPayments,
                bureauConsent = bureauConsent,
            ),
            status = status,
            closeReason = closeReason,
            createdAt = createdAt,
            updatedAt = updatedAt,
            submittedAt = submittedAt,
            statusChangedAt = statusChangedAt,
            version = version,
        )

    companion object {
        fun from(application: LoanApplication) =
            with(application) {
                ApplicationEntity(
                    id = id,
                    number = number,
                    companyId = company.id,
                    companyInn = company.inn,
                    companyName = company.name,
                    legalForm = company.legalForm,
                    createdBy = createdBy,
                    productCode = terms.productCode,
                    purpose = terms.purpose,
                    purposeComment = terms.purposeComment,
                    amount = terms.amount,
                    termMonths = terms.termMonths,
                    declaredRevenue12m = terms.declaredRevenue12m,
                    declaredMonthlyDebtPayments = terms.declaredMonthlyDebtPayments,
                    bureauConsent = terms.bureauConsent,
                    status = status,
                    closeReason = closeReason,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    submittedAt = submittedAt,
                    statusChangedAt = statusChangedAt,
                    version = version,
                )
            }
    }
}
