package ru.creditcabinet.company.controller

import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.company.domain.Company
import ru.creditcabinet.company.domain.CompanyDataPort
import ru.creditcabinet.company.domain.LegalForm
import ru.creditcabinet.user.domain.UserDataPort
import java.util.UUID

data class CompanyMemberResponse(val id: UUID, val fullName: String, val canSign: Boolean)

data class CompanyResponse(
    val id: UUID,
    val inn: String,
    val name: String?,
    val legalForm: LegalForm,
    val members: List<CompanyMemberResponse>,
)

@Tag(name = "Компания клиента")
@RestController
@RequestMapping("/api/v1/companies")
class CompanyController(
    private val companies: CompanyDataPort,
    private val users: UserDataPort,
) {
    /** The company the signed-in client acts for, with the people who represent it. */
    @Operation(summary = "Компания и представители текущего клиента; компания определяется подписанным токеном")
    @GetMapping("/me")
    fun mine(): CompanyResponse {
        val (_, companyId) = CurrentCaller.requireClient()
        val company = companies.findById(companyId) ?: throw NotFoundException("Компания не найдена")
        return company.toResponse(users.findByCompany(companyId).map { CompanyMemberResponse(it.id, it.fullName, it.canSign) })
    }
}

/** Company lookups for other services (credit needs the ИНН to ask the registries). Not routed by the gateway. */
@Hidden
@RestController
@RequestMapping("/internal/v1/companies")
class InternalCompanyController(private val companies: CompanyDataPort) {
    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): CompanyResponse =
        companies.findById(id)?.toResponse(emptyList()) ?: throw NotFoundException("Компания не найдена")
}

private fun Company.toResponse(members: List<CompanyMemberResponse>) = CompanyResponse(id, inn, name, legalForm, members)
