package ru.creditcabinet.sim.bank

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
import java.time.YearMonth
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

data class MonthlyTurnover(val month: YearMonth, val inflow: Long, val outflow: Long)

/** What the bank knows about a company from its own accounts: whether it is a client and its turnover. */
data class BankClientData(val inn: String, val client: Boolean, val settlementAccount: String?, val turnovers: List<MonthlyTurnover>)

@Tag(name = "Внутренние данные банка (симулятор)")
@RestController
@RequestMapping("/ext/bank/v1")
class BankDataController(private val dataset: Dataset, private val faults: Faults) {
    @Operation(summary = "Счёт компании в банке и обороты за 12 месяцев")
    @GetMapping("/clients/{inn}")
    fun client(@PathVariable inn: String): BankClientData {
        val company = dataset.find(inn)
        faults.enter(Source.BANK, company)
        company ?: throw NotFoundException("Компания с ИНН $inn неизвестна")
        val cv = company.bank.turnoverCv
        val revenue = company.fns?.revenue
        if (!company.bank.client ||
            cv == null ||
            revenue == null
        ) {
            return BankClientData(company.inn, company.bank.client, account(company.inn).takeIf { company.bank.client }, emptyList())
        }
        return BankClientData(company.inn, true, account(company.inn), turnovers(company.inn, revenue, cv))
    }

    private fun account(inn: String) = "40702810" + inn.padStart(12, '0')

    /**
     * Twelve months of incoming payments, a seasonal wave around 90% of the reported revenue
     * whose coefficient of variation is exactly [cv]: a sine of amplitude a has a standard
     * deviation of a/√2 over a full period.
     */
    private fun turnovers(inn: String, revenue: Long, cv: Double): List<MonthlyTurnover> {
        val mean = revenue * BANK_SHARE / 12
        val amplitude = cv * sqrt(2.0)
        val phase = (inn.takeLast(3).toInt() % 12) * PI / 6
        val last = YearMonth.now().minusMonths(1)
        return (0 until 12).map { k ->
            val inflow = (mean * (1 + amplitude * sin(2 * PI * k / 12 + phase))).roundToLong().coerceAtLeast(0)
            MonthlyTurnover(last.minusMonths((11 - k).toLong()), inflow, (inflow * OUTFLOW_SHARE).roundToLong())
        }
    }

    private companion object {
        const val BANK_SHARE = 0.9
        const val OUTFLOW_SHARE = 0.93
    }
}
