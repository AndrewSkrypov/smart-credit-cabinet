package ru.creditcabinet.assessment.domain

import java.time.LocalDate

/** The sources the pipeline asks, in the order it asks them. */
enum class SourceKind(val title: String) {
    BANK("Данные банка"),
    REGISTRY("ЕГРЮЛ / ЕГРИП"),
    TAX("ФНС"),
    BUREAU("Бюро кредитных историй"),
}

data class RegistryInfo(
    val inn: String,
    val ogrn: String,
    val fullName: String,
    val shortName: String,
    /** ACTIVE, LIQUIDATING, BANKRUPTCY or EXCLUDED, as the registry calls it. */
    val status: String,
    val statusTitle: String,
    val registrationDate: LocalDate,
    val okvedCode: String,
    val okvedName: String,
    val directorName: String,
    val address: String,
    val unreliableAddress: Boolean,
    val unreliableDirector: Boolean,
)

data class TaxInfo(
    val hasReports: Boolean,
    val reportingYear: Int?,
    val revenue: Long?,
    val expenses: Long?,
    val taxDebt: Long,
    val averageHeadcount: Int?,
    val taxRegime: String?,
    val massAddress: Boolean,
)

data class BureauObligation(
    val creditor: String,
    val type: String,
    val limit: Long,
    val balance: Long,
    val monthlyPayment: Long,
    val currentOverdueDays: Int,
)

data class BureauInfo(
    val obligations: List<BureauObligation>,
    val monthlyPaymentsTotal: Long,
    val currentOverdueDaysMax: Int,
    val worstOverdueDays24m: Int,
    val inquiries30d: Int,
)

data class BankInfo(val client: Boolean, val settlementAccount: String?, val monthlyInflows: List<Long>)

/** Everything the sources said about the company; the input of the scorecard. */
data class SourceData(val registry: RegistryInfo, val tax: TaxInfo, val bureau: BureauInfo, val bank: BankInfo)
