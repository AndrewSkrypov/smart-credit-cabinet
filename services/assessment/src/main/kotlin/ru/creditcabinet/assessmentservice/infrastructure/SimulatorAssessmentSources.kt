package ru.creditcabinet.assessmentservice.infrastructure

import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import ru.creditcabinet.assessment.domain.BankInfo
import ru.creditcabinet.assessment.domain.BureauInfo
import ru.creditcabinet.assessment.domain.BureauObligation
import ru.creditcabinet.assessment.domain.RegistryInfo
import ru.creditcabinet.assessment.domain.TaxInfo
import ru.creditcabinet.assessmentservice.application.AssessmentSourceUnavailable
import ru.creditcabinet.assessmentservice.application.AssessmentSources
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

class SimulatorAssessmentSources(private val client: RestClient, private val clock: Clock) : AssessmentSources {
    private data class Okved(val code: String, val name: String)

    private data class Director(val fullName: String)

    private data class Extract(
        val inn: String,
        val ogrn: String,
        val fullName: String,
        val shortName: String,
        val status: String,
        val statusTitle: String,
        val registrationDate: LocalDate,
        val okvedMain: Okved,
        val director: Director,
        val address: String,
        val unreliableAddress: Boolean,
        val unreliableDirector: Boolean,
    )

    private data class Summary(
        val hasReports: Boolean,
        val reportingYear: Int?,
        val revenue: Long?,
        val expenses: Long?,
        val taxDebt: Long,
        val averageHeadcount: Int?,
        val taxRegime: String?,
        val massAddress: Boolean,
    )

    private data class Report(
        val obligations: List<BureauObligation>,
        val monthlyPaymentsTotal: Long,
        val currentOverdueDaysMax: Int,
        val worstOverdueDays24m: Int,
        val inquiries30d: Int,
    )

    private data class Turnover(val inflow: Long)

    private data class Client(val client: Boolean, val settlementAccount: String?, val turnovers: List<Turnover>)

    override fun registry(inn: String): RegistryInfo {
        val extract = call { it.get().uri("/ext/registry/v1/companies/{inn}", inn).retrieve().body(Extract::class.java) }
            // Unknown to the registry: the business does not exist as far as the bank can tell.
            ?: return RegistryInfo(inn, "", "", "", "NOT_FOUND", "Нет в реестре", LocalDate.now(clock), "", "", "", "", false, false)
        return with(extract) {
            RegistryInfo(
                inn,
                ogrn,
                fullName,
                shortName,
                status,
                statusTitle,
                registrationDate,
                okvedMain.code,
                okvedMain.name,
                director.fullName,
                address,
                unreliableAddress,
                unreliableDirector,
            )
        }
    }

    override fun tax(inn: String): TaxInfo {
        val summary = call { it.get().uri("/ext/fns/v1/companies/{inn}/summary", inn).retrieve().body(Summary::class.java) }
            ?: return TaxInfo(false, null, null, null, 0, null, null, false)
        return with(summary) { TaxInfo(hasReports, reportingYear, revenue, expenses, taxDebt, averageHeadcount, taxRegime, massAddress) }
    }

    override fun bureau(inn: String, consentId: UUID): BureauInfo {
        val report = call {
            it.get().uri("/ext/bureau/v1/reports/{inn}", inn).header("X-Consent-Id", consentId.toString()).retrieve().body(Report::class.java)
        } ?: return BureauInfo(emptyList(), 0, 0, 0, 0)
        return with(report) { BureauInfo(obligations, monthlyPaymentsTotal, currentOverdueDaysMax, worstOverdueDays24m, inquiries30d) }
    }

    override fun bank(inn: String): BankInfo {
        val client = call { it.get().uri("/ext/bank/v1/clients/{inn}", inn).retrieve().body(Client::class.java) }
            ?: return BankInfo(false, null, emptyList())
        return BankInfo(client.client, client.settlementAccount, client.turnovers.map { it.inflow })
    }
    private fun <T> call(exchange: (RestClient) -> T?): T? = try {
        exchange(client)
    } catch (_: HttpClientErrorException.NotFound) {
        null
    } catch (_: RestClientException) {
        throw AssessmentSourceUnavailable()
    }
}
