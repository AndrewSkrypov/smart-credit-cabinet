package ru.creditcabinet.documents.application

import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.documents.ArtifactRecord
import ru.creditcabinet.documents.ArtifactRules
import ru.creditcabinet.documents.DocumentScope
import ru.creditcabinet.documents.domain.ArtifactLifecycle
import ru.creditcabinet.documents.domain.samePreparation
import java.util.UUID

/** The caller's ownership transaction surrounds prepare/retry, including row locks. */
class ArtifactOperations(
    private val scopes: DocumentRecords,
    private val records: ArtifactRecords,
    private val input: FrozenArtifactInput,
    private val files: FileStore,
    private val lifecycle: ArtifactLifecycle,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    fun list(scope: DocumentScope): List<ArtifactRecord> {
        scopes.verifyScope(scope)
        return records.artifacts(scope)
    }

    fun prepare(record: ArtifactRecord): ArtifactRecord {
        scopes.verifyScope(record.scope, bind = true)
        ArtifactRules.validate(record, input.decode(record.inputJson), lifecycle.templateVersion)
        records.insertArtifact(record)
        return list(record.scope).single { it.samePreparation(record) }
    }

    fun retry(scope: DocumentScope, id: UUID): ArtifactRecord {
        scopes.verifyScope(scope)
        val record = records.lockArtifact(scope, id) ?: throw NoSuchElementException()
        if (!lifecycle.needsRetry(record)) return record
        check(records.retireExpiredLease(id))
        val pending = lifecycle.retry(record, newId)
        if (record.templateVersion != lifecycle.templateVersion) return prepare(pending)
        check(records.resetPending(pending))
        return pending
    }

    fun bytes(scope: DocumentScope, id: UUID): ByteArray {
        val record = list(scope).firstOrNull { it.id == id } ?: throw NoSuchElementException()
        check(record.status == "READY")
        return readStoredBytes(files, record.storedName, record.backend, record.sizeBytes!!, record.checksum!!)
    }
}
