package ru.creditcabinet.sim.registry

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.sim.control.Faults
import ru.creditcabinet.sim.dataset.Dataset
import ru.creditcabinet.sim.dataset.Director
import ru.creditcabinet.sim.dataset.Founder
import ru.creditcabinet.sim.dataset.LegalForm
import ru.creditcabinet.sim.dataset.Okved
import ru.creditcabinet.sim.dataset.RegistryStatus
import ru.creditcabinet.sim.dataset.Source
import java.time.LocalDate

/** An extract from ЕГРЮЛ (organisations) or ЕГРИП (entrepreneurs), reduced to what the bank uses. */
data class RegistryExtract(
    val inn: String,
    val ogrn: String,
    val legalForm: LegalForm,
    val registry: String,
    val fullName: String,
    val shortName: String,
    val status: RegistryStatus,
    val statusTitle: String,
    val registrationDate: LocalDate,
    val okvedMain: Okved,
    val director: Director,
    val founders: List<Founder>,
    val address: String,
    /** The tax service marked the address or the director as unreliable ("недостоверные сведения"). */
    val unreliableAddress: Boolean,
    val unreliableDirector: Boolean,
    val extractDate: LocalDate,
)

@Tag(name = "ЕГРЮЛ / ЕГРИП (симулятор)")
@RestController
@RequestMapping("/ext/registry/v1")
class RegistryController(private val dataset: Dataset, private val faults: Faults) {
    @Operation(summary = "Выписка из ЕГРЮЛ/ЕГРИП по ИНН")
    @GetMapping("/companies/{inn}")
    fun extract(@PathVariable inn: String): RegistryExtract {
        val company = dataset.find(inn)
        faults.enter(Source.REGISTRY, company)
        company ?: throw NotFoundException("В ЕГРЮЛ и ЕГРИП нет записи с ИНН $inn")
        val today = LocalDate.now()
        return RegistryExtract(
            inn = company.inn,
            ogrn = company.ogrn,
            legalForm = company.legalForm,
            registry = if (company.legalForm == LegalForm.LEGAL_ENTITY) "ЕГРЮЛ" else "ЕГРИП",
            fullName = company.fullName,
            shortName = company.shortName,
            status = company.status,
            statusTitle = company.status.title,
            registrationDate = today.minusMonths(company.registeredMonthsAgo.toLong()),
            okvedMain = company.okved,
            director = company.director,
            founders = company.founders,
            address = company.address,
            unreliableAddress = company.unreliableAddress,
            unreliableDirector = company.unreliableDirector,
            extractDate = today,
        )
    }
}
