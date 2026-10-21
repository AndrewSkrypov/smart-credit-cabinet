package ru.creditcabinet.company.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Component
import ru.creditcabinet.company.domain.Company
import ru.creditcabinet.company.domain.CompanyDataPort
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "companies")
class CompanyEntity(
    @Id
    val id: UUID,
    @Column(nullable = false, unique = true, length = 12)
    val inn: String,
    val name: String?,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
) {
    fun toDomain() = Company(id = id, inn = inn, name = name, createdAt = createdAt)
}

interface CompanyJpaRepository : JpaRepository<CompanyEntity, UUID> {
    fun findByInn(inn: String): CompanyEntity?
}

@Component
class CompanyJpaAdapter(private val repository: CompanyJpaRepository) : CompanyDataPort {
    override fun findById(id: UUID): Company? = repository.findById(id).orElse(null)?.toDomain()

    override fun findByInn(inn: String): Company? = repository.findByInn(inn)?.toDomain()

    override fun save(company: Company): Company {
        repository.save(CompanyEntity(company.id, company.inn, company.name, company.createdAt))
        return company
    }
}
