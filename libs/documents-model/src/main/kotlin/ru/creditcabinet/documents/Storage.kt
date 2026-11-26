package ru.creditcabinet.documents

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

const val MAX_DOCUMENT_BYTES = 20 * 1024 * 1024

data class DocumentScope(val companyId: UUID, val applicationId: UUID)
data class DocumentRecord(
    val id: UUID, val scope: DocumentScope, val type: String, val fileName: String,
    val storedName: String, val sizeBytes: Long, val uploadedBy: UUID, val uploadedAt: Instant,
    val context: String, val backend: String, val checksum: String, val privateToBank: Boolean,
    val deletedAt: Instant? = null,
) {
    fun validate() {
        require(type in setOf("TAX_RETURN", "FINANCIAL_STATEMENTS", "EQUIPMENT_INVOICE", "BANK_STATEMENT", "OTHER"))
        require(context in setOf("BUSINESS", "CHAT") && backend in setOf("LOCAL", "S3"))
        require(sizeBytes in 1..MAX_DOCUMENT_BYTES && checksum.matches(Regex("[a-f0-9]{64}")))
        require(fileName.isNotBlank() && fileName.length <= 255 && '\u0000' !in fileName)
        require(storedName.matches(Regex("[A-Za-z0-9._-]{1,255}")) && storedName !in setOf(".", ".."))
        require(!privateToBank || (context == "BUSINESS" && type == "BANK_STATEMENT"))
    }
}

data class ArtifactRecord(
    val id: UUID, val scope: DocumentScope, val offerId: UUID, val kind: String, val phase: String,
    val templateVersion: Int, val inputHash: String, val inputJson: String, val title: String,
    val fileName: String, val contentType: String, val storedName: String, val backend: String,
    val createdAt: Instant, val status: String = "PENDING", val sizeBytes: Long? = null,
    val checksum: String? = null, val readyAt: Instant? = null,
)

fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** Storage never infers authorization; the Credit facade supplies an already authorized scope. */
interface DocumentsPort {
    fun list(scope: DocumentScope): List<DocumentRecord>
    fun put(record: DocumentRecord, bytes: ByteArray)
    fun read(scope: DocumentScope, id: UUID, context: String): ByteArray
    fun delete(scope: DocumentScope, id: UUID, context: String, at: Instant)
    fun artifacts(scope: DocumentScope): List<ArtifactRecord>
    fun prepare(record: ArtifactRecord): ArtifactRecord
    fun retry(scope: DocumentScope, id: UUID): ArtifactRecord
    fun artifactBytes(scope: DocumentScope, id: UUID): ByteArray
}
