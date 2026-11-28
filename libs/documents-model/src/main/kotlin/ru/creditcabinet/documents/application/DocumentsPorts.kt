package ru.creditcabinet.documents.application

import ru.creditcabinet.document.generated.ArtifactInput
import ru.creditcabinet.document.generated.ArtifactKind
import ru.creditcabinet.documents.ArtifactRecord
import ru.creditcabinet.documents.DocumentRecord
import ru.creditcabinet.documents.DocumentScope
import ru.creditcabinet.documents.domain.ArtifactLifecycle
import java.util.UUID

interface DocumentsOwnership {
    fun <T> owned(epoch: Long, action: () -> T): T
}

interface DocumentRecords {
    fun verifyScope(scope: DocumentScope, bind: Boolean = false)
    fun documents(scope: DocumentScope): List<DocumentRecord>
    fun saveDeleted(record: DocumentRecord)
}

interface ArtifactRecords {
    fun artifacts(scope: DocumentScope): List<ArtifactRecord>
    fun insertArtifact(record: ArtifactRecord)
    fun lockArtifact(scope: DocumentScope, id: UUID): ArtifactRecord?
    fun retireExpiredLease(id: UUID): Boolean
    fun resetPending(record: ArtifactRecord): Boolean
}

fun interface FrozenArtifactInput {
    fun decode(json: String): ArtifactInput
}

fun interface RenderArtifact {
    fun render(kind: ArtifactKind, input: ArtifactInput): ByteArray
}

data class ArtifactClaim(val record: ArtifactRecord, val token: UUID, val epoch: Long)

/**
 * The adapter claims atomically and publishes only under the same ownership epoch,
 * token and a freshly checked, unexpired lease. [publish] invokes [produceReady] only
 * after taking that row lock; storage writes and READY/outbox share its transaction.
 */
interface ArtifactJobs {
    fun claim(policy: ArtifactLifecycle): ArtifactClaim?
    fun publish(claim: ArtifactClaim, produceReady: () -> ArtifactRecord)
    fun fail(claim: ArtifactClaim, retrySeconds: Long)
}
