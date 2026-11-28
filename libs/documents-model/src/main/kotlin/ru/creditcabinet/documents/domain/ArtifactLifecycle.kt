package ru.creditcabinet.documents.domain

import ru.creditcabinet.document.generated.ArtifactKind
import ru.creditcabinet.documents.ArtifactRecord
import ru.creditcabinet.documents.DocumentConflict
import ru.creditcabinet.documents.MAX_DOCUMENT_BYTES
import ru.creditcabinet.documents.sha256
import java.time.Instant
import java.util.UUID

/** Values from the indexed job columns and immutable metadata, before claiming a lease. */
data class ArtifactPublicationState(
    val templateVersion: Int,
    val status: String,
    val hasChecksum: Boolean,
    val hasReadyAt: Boolean,
    val attempts: Int,
)

/** Template choice and lifecycle rules are independent of SQL, serialization and transport. */
class ArtifactLifecycle(val templateVersion: Int) {
    val maxAttempts: Int = 5
    val leaseSeconds: Long = 60
    val failureRetrySeconds: Long = 15

    fun eligible(state: ArtifactPublicationState): Boolean =
        state.templateVersion == templateVersion &&
            state.status != "READY" &&
            !state.hasChecksum &&
            !state.hasReadyAt &&
            state.attempts < maxAttempts

    /** READY is immutable even for an old/future template. Run before retiring a lease. */
    fun needsRetry(record: ArtifactRecord): Boolean {
        if (record.status == "READY") return false
        if (record.templateVersion == templateVersion && (record.readyAt != null || record.checksum != null)) throw DocumentConflict()
        return true
    }

    /** Run only after the adapter has atomically retired an expired/absent lease. */
    fun retry(record: ArtifactRecord, newId: () -> UUID): ArtifactRecord {
        if (record.templateVersion == templateVersion) return record.copy(status = "PENDING")
        require(record.templateVersion < templateVersion)
        val replacement = newId()
        return record.copy(
            id = replacement,
            templateVersion = templateVersion,
            storedName = "generated-$replacement.${ArtifactKind.valueOf(record.kind).extension}",
            status = "PENDING",
            sizeBytes = null,
            checksum = null,
            readyAt = null,
        )
    }

    fun verifyFrozenInput(record: ArtifactRecord) {
        check(sha256(record.inputJson.toByteArray()) == record.inputHash)
    }

    fun validateRendered(bytes: ByteArray) {
        require(bytes.size in 1..MAX_DOCUMENT_BYTES)
    }

    fun ready(record: ArtifactRecord, bytes: ByteArray, at: Instant): ArtifactRecord =
        record.copy(status = "READY", sizeBytes = bytes.size.toLong(), checksum = sha256(bytes), readyAt = at)
}

/** The database unique key; IDs and presentation names are not part of replay identity. */
fun ArtifactRecord.samePreparation(other: ArtifactRecord): Boolean =
    scope.applicationId == other.scope.applicationId &&
        offerId == other.offerId &&
        kind == other.kind &&
        phase == other.phase &&
        templateVersion == other.templateVersion &&
        inputHash == other.inputHash
