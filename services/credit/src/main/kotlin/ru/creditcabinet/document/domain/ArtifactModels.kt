package ru.creditcabinet.document.domain

import ru.creditcabinet.product.domain.Calculation
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

enum class ArtifactKind(val extension: String, val contentType: String) {
    CONTRACT_PDF("pdf", "application/pdf"),
    SCHEDULE_PDF("pdf", "application/pdf"),
    SCHEDULE_XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
}

enum class ArtifactPhase { PREVIEW, FINAL }

enum class ArtifactStatus { PENDING, READY, ERROR }

data class ArtifactGroup(val applicationId: UUID, val offerId: UUID, val templateVersion: Int, val inputHash: String)

data class ArtifactStorage(val name: String, val backend: StorageBackend)

data class ArtifactRecord(
    val id: UUID,
    val group: ArtifactGroup,
    val kind: ArtifactKind,
    val phase: ArtifactPhase,
    val title: String,
    val fileName: String,
    val contentType: String,
    val storage: ArtifactStorage,
    val status: ArtifactStatus,
    val sizeBytes: Long?,
    val checksum: String?,
    val createdAt: Instant,
    val readyAt: Instant?,
)

fun artifactChecksum(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

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
