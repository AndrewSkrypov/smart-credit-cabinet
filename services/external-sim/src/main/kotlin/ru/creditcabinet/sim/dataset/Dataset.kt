package ru.creditcabinet.sim.dataset

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import ru.creditcabinet.common.model.Inn
import kotlin.random.Random

/**
 * The synthetic businesses: the scenario companies of dataset/companies.json, and for any other
 * valid ИНН starting with "00" a typical healthy business generated from the number itself, the
 * same on every call. "00" is no Russian region, so no real company is ever described here;
 * other numbers are unknown to the registry.
 */
@Component
class Dataset {
    val scenarios: List<SyntheticCompany> =
        ClassPathResource("dataset/companies.json").inputStream.use { jacksonObjectMapper().readValue<List<SyntheticCompany>>(it) }

    private val byInn = scenarios.associateBy { it.inn }

    fun find(inn: String): SyntheticCompany? {
        byInn[inn]?.let { return it }
        if (!inn.startsWith(SYNTHETIC_PREFIX) || !Inn.isValid(inn)) return null
        return generate(inn)
    }

    private fun generate(inn: String): SyntheticCompany {
        val random = Random(inn.toLong())
        val legalEntity = inn.length == 10
        val tail = inn.takeLast(4)
        val (okvedCode, okvedName) = TYPICAL_OKVED[random.nextInt(TYPICAL_OKVED.size)]
        val revenue = (6 + random.nextInt(55)) * 1_000_000L
        val hasLoan = random.nextBoolean()
        return SyntheticCompany(
            inn = inn,
            ogrn = if (legalEntity) withCheckDigit("11500${inn.drop(2).take(7)}", 11) else withCheckDigit("31500${inn.drop(2).take(9)}", 13),
            legalForm = if (legalEntity) LegalForm.LEGAL_ENTITY else LegalForm.ENTREPRENEUR,
            fullName = if (legalEntity) {
                "Общество с ограниченной ответственностью «Тестовая компания $tail»"
            } else {
                "Индивидуальный предприниматель Тестов Тест Тестович ($tail)"
            },
            shortName = if (legalEntity) "ООО «Тестовая компания $tail»" else "ИП Тестов Т. Т. ($tail)",
            status = RegistryStatus.ACTIVE,
            registeredMonthsAgo = 24 + random.nextInt(73),
            okved = Okved(okvedCode, okvedName),
            director = Director("Тестов Тест Тестович", if (legalEntity) "Генеральный директор" else "Индивидуальный предприниматель"),
            founders = if (legalEntity) listOf(Founder("Тестов Тест Тестович", 100)) else emptyList(),
            address = "г. Москва, ул. Синтетическая, д. ${1 + random.nextInt(99)}",
            fns = FnsData(
                revenue = revenue,
                expenses = revenue * (85 + random.nextInt(10)) / 100,
                taxesPaid = revenue * 6 / 100,
                taxDebt = 0,
                averageHeadcount = 3 + random.nextInt(40),
                taxRegime = if (legalEntity) "OSN" else "USN",
                massAddress = false,
            ),
            bureau = BureauData(
                obligations = if (hasLoan) {
                    listOf(Obligation("Банк «Пример»", "LOAN", revenue / 10, revenue / 20, revenue / 400, 6 + random.nextInt(30)))
                } else {
                    emptyList()
                },
                worstOverdueDays24m = 0,
                inquiries30d = random.nextInt(3),
            ),
            bank = BankData(client = true, turnoverCv = 0.1 + random.nextInt(20) / 100.0),
        )
    }

    companion object {
        const val SYNTHETIC_PREFIX = "00"

        /** ОГРН (12 digits + check, modulo 11) or ОГРНИП (14 digits + check, modulo 13). */
        fun withCheckDigit(base: String, modulo: Long): String = base + (base.toLong() % modulo % 10)

        private val TYPICAL_OKVED = listOf(
            "47.19" to "Торговля розничная прочая в неспециализированных магазинах",
            "56.10" to "Деятельность ресторанов и услуги по доставке продуктов питания",
            "62.01" to "Разработка компьютерного программного обеспечения",
            "10.71" to "Производство хлеба и мучных кондитерских изделий",
            "96.02" to "Предоставление услуг парикмахерскими и салонами красоты",
        )
    }
}
