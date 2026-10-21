package ru.creditcabinet.company.domain

import ru.creditcabinet.common.model.Inn
import java.time.Instant
import java.util.UUID

enum class LegalForm {
    /** Organisation (ООО, АО): 10-digit ИНН. */
    LEGAL_ENTITY,

    /** Individual entrepreneur (ИП): 12-digit ИНН. */
    ENTREPRENEUR,
}

/**
 * A business connected to the cabinet. Only what access control needs lives here; the
 * registry profile (ОГРН, ОКВЭД, address, director) is fetched by the credit service.
 */
data class Company(
    val id: UUID = UUID.randomUUID(),
    val inn: String,
    /** From the state registry once it has been asked; null until then. */
    val name: String? = null,
    val createdAt: Instant = Instant.now(),
) {
    init {
        Inn.parse(inn)
    }

    val legalForm: LegalForm get() = if (Inn.parse(inn).isLegalEntity) LegalForm.LEGAL_ENTITY else LegalForm.ENTREPRENEUR
}

interface CompanyDataPort {
    fun findById(id: UUID): Company?
    fun findByInn(inn: String): Company?
    fun save(company: Company): Company
}
