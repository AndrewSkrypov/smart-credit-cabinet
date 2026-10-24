package ru.creditcabinet.document.generated

import ru.creditcabinet.product.domain.Calculation
import java.time.Instant
import java.util.UUID

enum class ArtifactKind(val extension: String, val contentType: String) {
    CONTRACT_PDF("pdf", "application/pdf"),
    SCHEDULE_PDF("pdf", "application/pdf"),
    SCHEDULE_XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
}

enum class ArtifactPhase { PREVIEW, FINAL }

/** Frozen inputs; retries never look up today's product, price or borrower name. */
data class ArtifactInput(
    val applicationId: UUID,
    val applicationNumber: Long?,
    val offerId: UUID,
    val companyName: String,
    val inn: String,
    val productName: String,
    val amount: Long,
    val termMonths: Int,
    val calculation: Calculation,
    val phase: ArtifactPhase,
    val contractText: String,
    val contractTextHash: String?,
    val legacySummary: Boolean,
    val signedAt: Instant?,
    val contractNumber: String?,
    val sourceCreatedAt: Instant,
)
