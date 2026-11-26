package ru.creditcabinet.company.application

import org.springframework.stereotype.Service
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.company.domain.Company
import ru.creditcabinet.company.domain.CompanyDataPort
import ru.creditcabinet.user.domain.User
import ru.creditcabinet.user.domain.UserDataPort
import java.util.UUID

data class CompanyWithMembers(val company: Company, val members: List<User>)

/** Application-layer queries for client company profiles and internal lookups. */
@Service
class CompanyService(
    private val companies: CompanyDataPort,
    private val users: UserDataPort,
) {
    fun getWithMembers(companyId: UUID): CompanyWithMembers {
        val company = companies.findById(companyId) ?: throw NotFoundException("Компания не найдена")
        return CompanyWithMembers(company, users.findByCompany(companyId))
    }

    fun get(companyId: UUID): Company =
        companies.findById(companyId) ?: throw NotFoundException("Компания не найдена")
}
