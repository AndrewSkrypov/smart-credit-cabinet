package ru.creditcabinet.application.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.LoanApplication
import java.util.UUID

interface ApplicationJpaRepository : JpaRepository<ApplicationEntity, UUID> {
    fun findByCompanyIdOrderByCreatedAtDesc(companyId: UUID): List<ApplicationEntity>
    fun findByStatusNotOrderByStatusChangedAtDesc(status: ApplicationStatus): List<ApplicationEntity>
    fun findByStatusInOrderByStatusChangedAtDesc(statuses: Collection<ApplicationStatus>): List<ApplicationEntity>
    fun existsByCompanyIdAndProductCodeAndStatusNotIn(companyId: UUID, productCode: String, statuses: Collection<ApplicationStatus>): Boolean
}

@Component
class ApplicationJpaAdapter(private val repository: ApplicationJpaRepository) : ApplicationRepository {
    override fun find(id: UUID): LoanApplication? = repository.findById(id).orElse(null)?.toDomain()

    // Flushed at once, so a stale version or a second active application fails inside the
    // caller's transaction, and the sequence number comes back to the caller.
    override fun save(application: LoanApplication): LoanApplication = repository.saveAndFlush(ApplicationEntity.from(application)).toDomain()

    override fun listByCompany(companyId: UUID): List<LoanApplication> = repository.findByCompanyIdOrderByCreatedAtDesc(companyId).map { it.toDomain() }

    override fun listForBank(statuses: Set<ApplicationStatus>?): List<LoanApplication> {
        val found = if (statuses.isNullOrEmpty()) {
            repository.findByStatusNotOrderByStatusChangedAtDesc(ApplicationStatus.DRAFT)
        } else {
            repository.findByStatusInOrderByStatusChangedAtDesc(statuses - ApplicationStatus.DRAFT)
        }
        return found.map { it.toDomain() }
    }

    override fun hasActive(companyId: UUID, productCode: String): Boolean =
        repository.existsByCompanyIdAndProductCodeAndStatusNotIn(companyId, productCode, ApplicationStatus.entries.filter { it.terminal })
}
