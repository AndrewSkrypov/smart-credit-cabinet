package ru.creditcabinet.sim.fns

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.sim.control.Faults
import ru.creditcabinet.sim.dataset.Dataset
import ru.creditcabinet.sim.dataset.Source
import java.time.LocalDate

/**
 * What the tax service publishes about a business («Прозрачный бизнес»): figures of the last
 * reported year. A business registered this year has no reports yet: [hasReports] is false.
 */
data class FnsSummary(
    val inn: String,
    val hasReports: Boolean,
    val reportingYear: Int?,
    val revenue: Long?,
    val expenses: Long?,
    val taxesPaid: Long?,
    val taxDebt: Long,
    val averageHeadcount: Int?,
    val taxRegime: String?,
    val massAddress: Boolean,
)

@Tag(name = "ФНС «Прозрачный бизнес» (симулятор)")
@RestController
@RequestMapping("/ext/fns/v1")
class FnsController(private val dataset: Dataset, private val faults: Faults) {
    @Operation(summary = "Финансовые показатели и налоговая задолженность за последний отчётный год")
    @GetMapping("/companies/{inn}/summary")
    fun summary(@PathVariable inn: String): FnsSummary {
        val company = dataset.find(inn)
        faults.enter(Source.FNS, company)
        company ?: throw NotFoundException("Налогоплательщик с ИНН $inn не найден")
        val fns = company.fns
        return FnsSummary(
            inn = company.inn,
            hasReports = fns != null,
            reportingYear = fns?.let { LocalDate.now().year - 1 },
            revenue = fns?.revenue,
            expenses = fns?.expenses,
            taxesPaid = fns?.taxesPaid,
            taxDebt = fns?.taxDebt ?: 0,
            averageHeadcount = fns?.averageHeadcount,
            taxRegime = fns?.taxRegime,
            massAddress = fns?.massAddress ?: false,
        )
    }
}
