package ru.creditcabinet.application.infrastructure

import jakarta.persistence.LockModeType
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import ru.creditcabinet.application.domain.ApplicationCreationConflict
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.LoanApplication
import java.util.UUID

interface ApplicationJpaRepository : JpaRepository<ApplicationEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from ApplicationEntity a where a.id = :id")
    fun findForUpdate(@Param("id") id: UUID): ApplicationEntity?

    fun findByCompanyIdOrderByCreatedAtDesc(companyId: UUID): List<ApplicationEntity>
    fun findByAgentIdOrderByCreatedAtDesc(agentId: UUID): List<ApplicationEntity>
    fun findBySubmittedAtIsNotNullOrderByStatusChangedAtDesc(): List<ApplicationEntity>
    fun findBySubmittedAtIsNotNullAndStatusInOrderByStatusChangedAtDesc(statuses: Collection<ApplicationStatus>): List<ApplicationEntity>
    fun existsByCompanyIdAndProductCodeAndStatusNotIn(companyId: UUID, productCode: String, statuses: Collection<ApplicationStatus>): Boolean
}

@Component
class ApplicationJpaAdapter(private val repository: ApplicationJpaRepository) : ApplicationRepository {
    override fun find(id: UUID): LoanApplication? = repository.findById(id).orElse(null)?.toDomain()
    override fun findForUpdate(id: UUID): LoanApplication? = repository.findForUpdate(id)?.toDomain()

    // Flushed at once, so a stale version or a second active application fails inside the
    // caller's transaction, and the sequence number comes back to the caller.
    override fun save(application: LoanApplication): LoanApplication = repository.saveAndFlush(ApplicationEntity.from(application)).toDomain()

    override fun create(application: LoanApplication): LoanApplication = try {
        save(application)
    } catch (failure: DataIntegrityViolationException) {
        throw ApplicationCreationConflict(failure)
    }

    override fun listByCompany(companyId: UUID): List<LoanApplication> = repository.findByCompanyIdOrderByCreatedAtDesc(companyId).map { it.toDomain() }

    override fun listByAgent(agentId: UUID): List<LoanApplication> = repository.findByAgentIdOrderByCreatedAtDesc(agentId).map { it.toDomain() }

    override fun listForBank(statuses: Set<ApplicationStatus>?): List<LoanApplication> {
        val found = if (statuses.isNullOrEmpty()) {
            repository.findBySubmittedAtIsNotNullOrderByStatusChangedAtDesc()
        } else {
            repository.findBySubmittedAtIsNotNullAndStatusInOrderByStatusChangedAtDesc(statuses)
        }
        return found.map { it.toDomain() }
    }

    override fun hasActive(companyId: UUID, productCode: String): Boolean =
        repository.existsByCompanyIdAndProductCodeAndStatusNotIn(companyId, productCode, ApplicationStatus.entries.filter { it.terminal })
}
