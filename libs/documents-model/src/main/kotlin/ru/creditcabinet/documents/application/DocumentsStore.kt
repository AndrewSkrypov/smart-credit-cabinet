package ru.creditcabinet.documents.application

import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.documents.DocumentConflict
import ru.creditcabinet.documents.DocumentRecord
import ru.creditcabinet.documents.DocumentScope
import ru.creditcabinet.documents.DocumentsPort
import ru.creditcabinet.documents.MAX_DOCUMENT_BYTES
import ru.creditcabinet.documents.StoreDocument
import ru.creditcabinet.documents.sha256
import java.time.Instant
import java.util.UUID

/** Use cases operate on an authorized scope supplied by Credit; adapters own fencing. */
class DocumentsStore(
    private val records: DocumentRecords,
    private val uploads: StoreDocument,
    private val artifacts: ArtifactOperations,
    private val generation: GenerateArtifact,
    private val ownership: DocumentsOwnership,
    private val files: FileStore,
) : DocumentsPort {
    fun <T> owned(epoch: Long, action: () -> T): T = ownership.owned(epoch, action)

    override fun list(scope: DocumentScope): List<DocumentRecord> {
        records.verifyScope(scope)
        return records.documents(scope)
    }

    override fun put(record: DocumentRecord, bytes: ByteArray) {
        records.verifyScope(record.scope, bind = true)
        uploads.execute(record, bytes)
    }

    private fun document(scope: DocumentScope, id: UUID, context: String): DocumentRecord =
        list(scope).firstOrNull { it.id == id && it.context == context } ?: throw NoSuchElementException()

    override fun read(scope: DocumentScope, id: UUID, context: String): ByteArray {
        val record = document(scope, id, context)
        return readStoredBytes(files, record.storedName, record.backend, record.sizeBytes, record.checksum)
    }

    override fun delete(scope: DocumentScope, id: UUID, context: String, at: Instant) {
        val record = document(scope, id, context)
        if (record.deletedAt != null) {
            if (record.deletedAt != at) throw DocumentConflict()
            return
        }
        records.saveDeleted(record.copy(deletedAt = at))
    }

    override fun artifacts(scope: DocumentScope) = artifacts.list(scope)
    override fun prepare(record: ru.creditcabinet.documents.ArtifactRecord) = artifacts.prepare(record)
    override fun retry(scope: DocumentScope, id: UUID) = artifacts.retry(scope, id)
    override fun artifactBytes(scope: DocumentScope, id: UUID) = artifacts.bytes(scope, id)
    fun generateOne() = generation.execute()
}

internal fun readStoredBytes(files: FileStore, name: String, backend: String, size: Long, checksum: String): ByteArray {
    val file = files.get(name, StorageBackend.valueOf(backend)) ?: error("Storage unavailable")
    val bytes = file.content.use { it.readNBytes(MAX_DOCUMENT_BYTES + 1) }
    check(bytes.size.toLong() == size && file.sizeBytes == size && sha256(bytes) == checksum)
    return bytes
}
