package ru.creditcabinet.sim.dataset

/** One synthetic business as all simulated systems know it. Mirrors dataset/companies.json. */
data class SyntheticCompany(
    val inn: String,
    val ogrn: String,
    val legalForm: LegalForm,
    val fullName: String,
    val shortName: String,
    val status: RegistryStatus,
    val registeredMonthsAgo: Int,
    val okved: Okved,
    val director: Director,
    val founders: List<Founder> = emptyList(),
    val address: String,
    val unreliableAddress: Boolean = false,
    val unreliableDirector: Boolean = false,
    /** Null: the tax service has no reports yet (a business registered this year). */
    val fns: FnsData?,
    val bureau: BureauData,
    val bank: BankData,
    /** Sources that always fail for this company, e.g. {"BUREAU": "DOWN"}. */
    val faults: Map<Source, FaultMode> = emptyMap(),
    /** What this company demonstrates; null for companies generated on the fly. */
    val scenario: Scenario? = null,
)

enum class LegalForm { LEGAL_ENTITY, ENTREPRENEUR }

enum class RegistryStatus(val title: String) {
    ACTIVE("Действующее"),
    LIQUIDATING("В процессе ликвидации"),
    BANKRUPTCY("В процессе банкротства"),
    EXCLUDED("Исключено из реестра"),
}

data class Okved(val code: String, val name: String)

data class Director(val fullName: String, val position: String)

data class Founder(val name: String, val sharePercent: Int)

data class FnsData(
    val revenue: Long,
    val expenses: Long,
    val taxesPaid: Long,
    val taxDebt: Long,
    val averageHeadcount: Int,
    val taxRegime: String,
    val massAddress: Boolean,
)

data class Obligation(
    val creditor: String,
    val type: String,
    val limit: Long,
    val balance: Long,
    val monthlyPayment: Long,
    val openedMonthsAgo: Int,
    val currentOverdueDays: Int = 0,
)

data class BureauData(val obligations: List<Obligation>, val worstOverdueDays24m: Int, val inquiries30d: Int)

/** Whether the company has an account in the bank and how even its monthly turnover is (coefficient of variation). */
data class BankData(val client: Boolean, val turnoverCv: Double?)

data class ScenarioRequest(
    val productCode: String,
    val purpose: String,
    val amount: Long,
    val termMonths: Int,
    val declaredRevenue12m: Long,
)

data class Scenario(val code: String, val title: String, val expected: String, val request: ScenarioRequest)

/** The simulated external systems; each can be made slow or unavailable from the control panel. */
enum class Source(val title: String) {
    REGISTRY("ЕГРЮЛ / ЕГРИП"),
    FNS("ФНС «Прозрачный бизнес»"),
    BUREAU("Бюро кредитных историй"),
    BANK("Внутренние данные банка"),
    SIGNING("Сервис подписания"),
    CORE("АБС (выдача)"),
}

enum class FaultMode { OK, SLOW, DOWN }
