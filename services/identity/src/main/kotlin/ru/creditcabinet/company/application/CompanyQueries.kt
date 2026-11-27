package ru.creditcabinet.company.application

import ru.creditcabinet.company.domain.Company
import ru.creditcabinet.company.domain.CompanyDataPort
import ru.creditcabinet.identity.domain.IdentityFailure.NotFound
import ru.creditcabinet.user.domain.User
import ru.creditcabinet.user.domain.UserDataPort
import java.util.UUID

class CompanyQueries(private val companies: CompanyDataPort, private val users: UserDataPort) {
    fun get(id: UUID): Company = companies.findById(id) ?: throw NotFound("Компания не найдена")

    fun getWithMembers(id: UUID): CompanyWithMembers = CompanyWithMembers(get(id), users.findByCompany(id))
}

data class CompanyWithMembers(val company: Company, val members: List<User>)
