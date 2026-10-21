package ru.creditcabinet.document.domain

import ru.creditcabinet.application.domain.LegalForm
import ru.creditcabinet.product.domain.Product
import java.io.InputStream
import java.time.Instant
import java.util.UUID

enum class DocumentType(val title: String) {
    TAX_RETURN("Налоговая декларация за последний год"),
    FINANCIAL_STATEMENTS("Бухгалтерская отчётность (формы 1 и 2)"),
    EQUIPMENT_INVOICE("Счёт или спецификация на оборудование"),
    BANK_STATEMENT("Выписка по счёту в другом банке за 12 месяцев"),
    OTHER("Другой документ"),
}

data class ApplicationDocument(
    val id: UUID = UUID.randomUUID(),
    val applicationId: UUID,
    val type: DocumentType,
    val fileName: String,
    val storedName: String,
    val sizeBytes: Long,
    val uploadedBy: UUID,
    val uploadedAt: Instant,
)

/** A document the bank needs for this application, and why. */
data class DocumentRequirement(val type: DocumentType, val reason: String)

/**
 * Which documents an application needs, by the rules of docs/process-model.md, section 4. The
 * bank statement depends on whether the company has an account in the bank, which is known
 * only after the internal systems are asked: until then [bankClient] is null and it is not required.
 */
object DocumentRequirements {
    const val LARGE_AMOUNT = 3_000_000L
    const val STATEMENT_AMOUNT = 1_000_000L

    fun required(product: Product, amount: Long, legalForm: LegalForm, bankClient: Boolean? = null): List<DocumentRequirement> =
        buildList {
            if (amount > LARGE_AMOUNT) add(DocumentRequirement(DocumentType.TAX_RETURN, "Сумма больше 3 млн ₽"))
            if (amount > LARGE_AMOUNT && legalForm == LegalForm.LEGAL_ENTITY) {
                add(DocumentRequirement(DocumentType.FINANCIAL_STATEMENTS, "Организация, сумма больше 3 млн ₽"))
            }
            if (product.code ==
                Product.INVESTMENT
            ) {
                add(DocumentRequirement(DocumentType.EQUIPMENT_INVOICE, "Инвестиционный кредит выдаётся под конкретную покупку"))
            }
            if (bankClient == false && amount > STATEMENT_AMOUNT) {
                add(DocumentRequirement(DocumentType.BANK_STATEMENT, "У компании нет счёта в банке, сумма больше 1 млн ₽"))
            }
        }
}

interface DocumentRepository {
    fun list(applicationId: UUID): List<ApplicationDocument>
    fun find(applicationId: UUID, documentId: UUID): ApplicationDocument?
    fun save(document: ApplicationDocument)
    fun markDeleted(documentId: UUID, at: Instant)
}

/** A stored object opened for reading; the caller closes [content]. */
class StoredFile(val sizeBytes: Long, val content: InputStream)

/** Where file bytes live: a local volume under docker compose, S3-compatible storage elsewhere. */
interface FileStore {
    fun put(storedName: String, content: InputStream, sizeBytes: Long)

    /** Null when no object with this name exists. */
    fun get(storedName: String): StoredFile?
}
