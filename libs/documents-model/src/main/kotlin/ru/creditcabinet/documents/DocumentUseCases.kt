package ru.creditcabinet.documents

import ru.creditcabinet.document.domain.DocumentScanner
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.generated.ArtifactInput
import ru.creditcabinet.document.generated.ArtifactKind

interface DocumentMetadata {
    fun find(id: java.util.UUID): DocumentRecord?
    fun reserve(record: DocumentRecord)
    fun insert(record: DocumentRecord)
}

/** Transaction and scope fencing surround the use case in the inbound adapter. */
class StoreDocument(private val metadata: DocumentMetadata, private val objects: FileStore, private val scanner: DocumentScanner) {
    fun execute(record: DocumentRecord, bytes: ByteArray) {
        record.validate()
        require(record.deletedAt == null && bytes.size.toLong() == record.sizeBytes && sha256(bytes) == record.checksum)
        val old = metadata.find(record.id)
        if (old != null) { require(old == record); return }
        metadata.reserve(record)
        val concurrent = metadata.find(record.id)
        if (concurrent != null) { require(concurrent == record); return }
        scanner.scan(bytes)
        val orphan = objects.get(record.storedName, StorageBackend.valueOf(record.backend))
        if (orphan != null) {
            val previous = orphan.content.use { it.readNBytes(MAX_DOCUMENT_BYTES + 1) }
            require(orphan.sizeBytes == record.sizeBytes && previous.contentEquals(bytes))
        } else {
            objects.put(record.storedName, bytes.inputStream(), record.sizeBytes, StorageBackend.valueOf(record.backend))
        }
        metadata.insert(record)
    }
}

object ArtifactRules {
    fun validate(record: ArtifactRecord, input: ArtifactInput, templateVersion: Int) {
        require(record.inputJson.toByteArray().size <= 1024 * 1024 && sha256(record.inputJson.toByteArray()) == record.inputHash)
        require(input.applicationId == record.scope.applicationId && input.offerId == record.offerId && input.phase.name == record.phase)
        require(record.templateVersion == templateVersion && record.kind in ArtifactKind.entries.map { it.name })
        require(record.status == "PENDING" && record.sizeBytes == null && record.checksum == null)
        require(record.backend in setOf("LOCAL", "S3") && record.storedName.matches(Regex("generated-[a-f0-9-]+\\.(pdf|xlsx)")))
        val kind = ArtifactKind.valueOf(record.kind)
        require(record.contentType == kind.contentType && record.fileName.endsWith(".${kind.extension}"))
        require(record.title.length <= 255 && record.fileName.length <= 255 && record.contentType.length <= 150)
        require(input.calculation.schedule.size in 1..600)
        if (input.contractTextHash != null) require(sha256(input.contractText.toByteArray()) == input.contractTextHash)
        if (record.kind == "CONTRACT_PDF") require(record.phase == "FINAL" && (input.contractTextHash != null || input.legacySummary))
    }
}
