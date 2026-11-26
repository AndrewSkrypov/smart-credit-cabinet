package ru.creditcabinet.document.application

import ru.creditcabinet.document.domain.ArtifactGroup
import ru.creditcabinet.document.domain.ArtifactInput
import ru.creditcabinet.document.domain.ArtifactKind
import ru.creditcabinet.document.domain.ArtifactPhase
import ru.creditcabinet.document.domain.ArtifactRecord
import ru.creditcabinet.document.domain.ArtifactStorage
import java.time.Instant
import java.util.UUID

data class ArtifactDownload(val metadata: ArtifactRecord, val bytes: ByteArray)

interface GeneratedArtifactOperations {
    fun list(applicationId: UUID): List<ArtifactRecord>

    fun retry(applicationId: UUID, artifactId: UUID): ArtifactRecord

    fun download(applicationId: UUID, artifactId: UUID): ArtifactDownload
}

interface GeneratedArtifactJobs {
    fun prepareFinalLoans()

    fun notifyReadyGroups(): Int

    fun generateOne(): Boolean
}

data class FrozenArtifactInput(val json: String, val hash: String)

interface ArtifactInputs {
    fun freeze(input: ArtifactInput): FrozenArtifactInput

    fun restore(frozen: FrozenArtifactInput): ArtifactInput
}

interface ArtifactRendering {
    val templateVersion: Int

    fun title(kind: ArtifactKind, input: ArtifactInput): String

    fun render(kind: ArtifactKind, input: ArtifactInput): ByteArray
}

data class ArtifactRegistration(
    val id: UUID,
    val kind: ArtifactKind,
    val title: String,
    val fileName: String,
    val storage: ArtifactStorage,
)

data class QueuedArtifact(val id: UUID, val kind: ArtifactKind, val storage: ArtifactStorage, val input: FrozenArtifactInput)

interface GeneratedArtifactStore {
    fun register(
        group: ArtifactGroup,
        phase: ArtifactPhase,
        inputJson: String,
        artifacts: List<ArtifactRegistration>,
        now: Instant,
    ): List<ArtifactRecord>

    fun loansAwaitingFiles(templateVersion: Int): List<UUID>

    fun readyGroups(templateVersion: Int): List<ArtifactGroup>

    /** Lock and claim within the caller's notification transaction. */
    fun claimReadyNotice(group: ArtifactGroup, now: Instant): Boolean

    /** The caller holds the transaction through the bounded write and status update. */
    fun lockNextArtifact(templateVersion: Int, now: Instant): QueuedArtifact?

    fun markReady(id: UUID, sizeBytes: Long, checksum: String, now: Instant)

    fun markGenerationFailed(id: UUID, now: Instant)

    fun find(applicationId: UUID, artifactId: UUID, lock: Boolean = false): ArtifactRecord?

    fun createReplacement(record: ArtifactRecord, replacementId: UUID, templateVersion: Int, now: Instant): UUID

    fun reset(id: UUID, now: Instant)

    fun markStorageUnavailable(id: UUID, now: Instant)
}
