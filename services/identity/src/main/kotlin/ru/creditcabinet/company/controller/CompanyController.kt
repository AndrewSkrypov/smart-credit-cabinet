package ru.creditcabinet.company.controller

import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.company.application.CompanyService
import ru.creditcabinet.company.domain.Company
import ru.creditcabinet.company.domain.LegalForm
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
class CompanyController(private val companyService: CompanyService) {
    /** The company the signed-in client acts for, with the people who represent it. */
    @Operation(summary = "Компания и представители текущего клиента; компания определяется подписанным токеном")
    @GetMapping("/me")
    fun mine(): CompanyResponse {
        val (_, companyId) = CurrentCaller.requireClient()
        val (company, members) = companyService.getWithMembers(companyId)
        return company.toResponse(members.map { CompanyMemberResponse(it.id, it.fullName, it.canSign) })
    }
}

/** Company lookups for other services (credit needs the ИНН to ask the registries). Not routed by the gateway. */
@Hidden
@RestController
@RequestMapping("/internal/v1/companies")
class InternalCompanyController(private val companyService: CompanyService) {
    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): CompanyResponse =
        companyService.get(id).toResponse(emptyList())
}

private fun Company.toResponse(members: List<CompanyMemberResponse>) = CompanyResponse(id, inn, name, legalForm, members)
