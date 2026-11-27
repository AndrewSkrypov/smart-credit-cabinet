package ru.creditcabinet.document.generated.application

import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.generated.ArtifactInput
import ru.creditcabinet.document.generated.ArtifactKind
import ru.creditcabinet.document.generated.ArtifactPhase
import ru.creditcabinet.documents.ArtifactRecord
import java.time.Instant
import java.util.UUID

data class ArtifactInputKey(val applicationId: UUID, val offerId: UUID, val phase: ArtifactPhase, val hash: String)

data class PendingArtifactInput(val applicationId: UUID, val input: ArtifactInput, val backend: StorageBackend)

data class ArtifactFile(val storedName: String, val backend: StorageBackend)

data class ArtifactWork(val id: UUID, val kind: ArtifactKind, val inputJson: String, val inputHash: String, val file: ArtifactFile)

interface GeneratedArtifactStore {
    fun frozenFinal(applicationId: UUID): ArtifactInput?

    fun freeze(key: ArtifactInputKey, canonical: String, backend: StorageBackend): String

    fun requestedBackend(key: ArtifactInputKey): StorageBackend?

    fun pendingInputs(): List<PendingArtifactInput>

    fun markDelivered(key: ArtifactInputKey)

    fun isDelivered(key: ArtifactInputKey): Boolean

    fun isReferenced(key: ArtifactInputKey): Boolean

    fun loansWithoutRemoteArtifacts(): List<UUID>

    fun loansWithoutLocalArtifacts(templateVersion: Int): List<UUID>

    fun putLocal(record: ArtifactRecord, at: Instant)

    fun listLocal(key: ArtifactInputKey, templateVersion: Int): List<ArtifactView>

    fun readyLocalGroups(templateVersion: Int): List<ArtifactInputKey>

    fun lockGroupStatuses(key: ArtifactInputKey, templateVersion: Int): List<ArtifactStatus>

    fun readyRemoteApplications(templateVersion: Int): List<UUID>

    fun claimNotice(key: ArtifactInputKey, templateVersion: Int, at: Instant): Boolean

    fun lockNext(templateVersion: Int, at: Instant): ArtifactWork?

    fun markReady(id: UUID, size: Long, checksum: String, at: Instant)

    fun markFailed(id: UUID, retryAt: Instant, at: Instant)

    fun findLocal(applicationId: UUID, id: UUID, lock: Boolean = false): ArtifactView?

    fun templateVersion(id: UUID): Int?

    fun replaceLegacy(id: UUID, replacement: UUID, fileName: String, templateVersion: Int, at: Instant): UUID

    fun reset(id: UUID, nextAttemptAt: Instant, updatedAt: Instant)

    fun file(id: UUID): ArtifactFile
}

interface ArtifactInputCodec {
    fun encode(input: ArtifactInput): String

    fun encodeBytes(input: ArtifactInput): ByteArray

    fun decode(encoded: String): ArtifactInput
}
