package ru.creditcabinet.sim.bureau

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.sim.control.Faults
import ru.creditcabinet.sim.dataset.Dataset
import ru.creditcabinet.sim.dataset.Source
import java.time.LocalDate

data class BureauObligation(
    val creditor: String,
    val type: String,
    val limit: Long,
    val balance: Long,
    val monthlyPayment: Long,
    val openedAt: LocalDate,
    val currentOverdueDays: Int,
)

/** A credit report of a business: current obligations, payment discipline, recent inquiries. */
data class CreditReport(
    val inn: String,
    val reportDate: LocalDate,
    val obligations: List<BureauObligation>,
    val monthlyPaymentsTotal: Long,
    val currentOverdueDaysMax: Int,
    val worstOverdueDays24m: Int,
    val inquiries30d: Int,
)

@Tag(name = "Бюро кредитных историй (симулятор)")
@RestController
@RequestMapping("/ext/bureau/v1")
class BureauController(private val dataset: Dataset, private val faults: Faults) {
    @Operation(summary = "Кредитный отчёт по ИНН (только с согласием клиента)")
    @GetMapping("/reports/{inn}")
    fun report(
        @PathVariable inn: String,
        @Parameter(description = "Идентификатор согласия субъекта на запрос кредитной истории")
        @RequestHeader("X-Consent-Id", required = false) consentId: String?,
    ): CreditReport {
        // A bureau answers only with the borrower's consent on record.
        if (consentId.isNullOrBlank()) throw BadRequestException("Без согласия субъекта кредитная история не выдаётся")
        val company = dataset.find(inn)
        faults.enter(Source.BUREAU, company)
        company ?: throw NotFoundException("Субъект с ИНН $inn в бюро не найден")
        val today = LocalDate.now()
        val obligations = company.bureau.obligations.map {
            BureauObligation(
                it.creditor,
                it.type,
                it.limit,
                it.balance,
                it.monthlyPayment,
                today.minusMonths(it.openedMonthsAgo.toLong()),
                it.currentOverdueDays,
            )
        }
        return CreditReport(
            inn = company.inn,
            reportDate = today,
            obligations = obligations,
            monthlyPaymentsTotal = obligations.sumOf { it.monthlyPayment },
            currentOverdueDaysMax = obligations.maxOfOrNull { it.currentOverdueDays } ?: 0,
            worstOverdueDays24m = company.bureau.worstOverdueDays24m,
            inquiries30d = company.bureau.inquiries30d,
        )
    }
}
