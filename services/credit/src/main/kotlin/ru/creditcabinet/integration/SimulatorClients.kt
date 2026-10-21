package ru.creditcabinet.integration

import io.github.resilience4j.circuitbreaker.CallNotPermittedException
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient
import ru.creditcabinet.assessment.domain.BankInfo
import ru.creditcabinet.assessment.domain.BureauInfo
import ru.creditcabinet.assessment.domain.BureauObligation
import ru.creditcabinet.assessment.domain.RegistryInfo
import ru.creditcabinet.assessment.domain.TaxInfo
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Calls of the external systems (the external-sim service locally), each behind its own circuit
 * breaker: after repeated failures a source is not called for a while and the step is retried
 * later, instead of every job waiting for the same dead timeout. Timeouts are short: a pipeline
 * step must not hang on a slow source.
 */
@Component
class ExternalCalls(
    @Value("\${cabinet.external.url:}") url: String,
    @Value("\${cabinet.external.api-key:}") apiKey: String,
    @Value("\${cabinet.external.read-timeout:PT5S}") readTimeout: Duration,
    builder: RestClient.Builder,
) {
    private val client: RestClient? = url.takeIf { it.isNotBlank() }?.let {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(2))
            setReadTimeout(readTimeout)
        }
        builder.clone().baseUrl(it).defaultHeader("X-Api-Key", apiKey).requestFactory(requestFactory).build()
    }

    private val breakers = CircuitBreakerRegistry.of(
        CircuitBreakerConfig.custom()
            .slidingWindowSize(6)
            .minimumNumberOfCalls(3)
            .failureRateThreshold(50f)
            .waitDurationInOpenState(Duration.ofSeconds(20))
            .permittedNumberOfCallsInHalfOpenState(1)
            // A 4xx (not found, wrong signing code) is an answer, not a failure of the source.
            .ignoreExceptions(HttpClientErrorException::class.java)
            .build(),
    )

    fun breaker(source: String): CircuitBreaker = breakers.circuitBreaker(source)

    /** Runs [call] for [source]; a 404 gives null, any other failure becomes [SourceUnavailableException]. */
    fun <T> call(source: String, call: (RestClient) -> T): T? {
        val client = client ?: throw SourceUnavailableException(source, "$source: адрес внешних систем не настроен")
        return try {
            breaker(source).executeSupplier { call(client) }
        } catch (exception: HttpClientErrorException.NotFound) {
            null
        } catch (exception: CallNotPermittedException) {
            throw SourceUnavailableException(source, "$source: источник временно не опрашивается после серии сбоев")
        } catch (exception: HttpClientErrorException) {
            if (exception.statusCode ==
                HttpStatus.BAD_REQUEST
            ) {
                throw IllegalStateException("$source: запрос отклонён — ${exception.responseBodyAsString}", exception)
            }
            throw SourceUnavailableException(source, "$source: ошибка ${exception.statusCode.value()}")
        } catch (exception: Exception) {
            throw SourceUnavailableException(source, "$source: ${exception.message ?: exception.javaClass.simpleName}")
        }
    }
}

@Component
class SimulatorCompanySources(private val calls: ExternalCalls) : CompanySources {
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
        val extract = calls.call("ЕГРЮЛ/ЕГРИП") { it.get().uri("/ext/registry/v1/companies/{inn}", inn).retrieve().body(Extract::class.java) }
            // Unknown to the registry: the business does not exist as far as the bank can tell.
            ?: return RegistryInfo(inn, "", "", "", "NOT_FOUND", "Нет в реестре", LocalDate.now(), "", "", "", "", false, false)
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
        val summary = calls.call("ФНС") { it.get().uri("/ext/fns/v1/companies/{inn}/summary", inn).retrieve().body(Summary::class.java) }
            ?: return TaxInfo(false, null, null, null, 0, null, null, false)
        return with(summary) { TaxInfo(hasReports, reportingYear, revenue, expenses, taxDebt, averageHeadcount, taxRegime, massAddress) }
    }

    override fun bureau(inn: String, consentId: String): BureauInfo {
        val report = calls.call("БКИ") {
            it.get().uri("/ext/bureau/v1/reports/{inn}", inn).header("X-Consent-Id", consentId).retrieve().body(Report::class.java)
        } ?: return BureauInfo(emptyList(), 0, 0, 0, 0)
        return with(report) { BureauInfo(obligations, monthlyPaymentsTotal, currentOverdueDaysMax, worstOverdueDays24m, inquiries30d) }
    }

    override fun bank(inn: String): BankInfo {
        val client = calls.call("Данные банка") { it.get().uri("/ext/bank/v1/clients/{inn}", inn).retrieve().body(Client::class.java) }
            ?: return BankInfo(false, null, emptyList())
        return BankInfo(client.client, client.settlementAccount, client.turnovers.map { it.inflow })
    }
}

@Component
class SimulatorSigningGateway(private val calls: ExternalCalls) : SigningGateway {
    private data class Session(val sessionId: UUID, val code: String, val expiresAt: Instant, val signatureId: UUID?, val signedAt: Instant?)

    override fun start(documentId: String, title: String, text: String, signerName: String): SigningStarted {
        val session = calls.call("Подписание") {
            it.post().uri("/ext/signing/v1/sessions").contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("documentId" to documentId, "documentTitle" to title, "documentText" to text, "signerName" to signerName))
                .retrieve().body(Session::class.java)
        } ?: throw SourceUnavailableException("Подписание", "Подписание: пустой ответ")
        return SigningStarted(session.sessionId, session.code, session.expiresAt)
    }

    override fun confirm(sessionId: UUID, code: String): SigningResult =
        try {
            val session = calls.call("Подписание") {
                it.post().uri("/ext/signing/v1/sessions/{id}/confirm", sessionId).contentType(MediaType.APPLICATION_JSON)
                    .body(mapOf("code" to code)).retrieve().body(Session::class.java)
            } ?: return SigningResult.Refused("Сессия подписания не найдена — запросите новый код")
            SigningResult.Signed(session.signatureId!!, session.signedAt!!)
        } catch (exception: IllegalStateException) {
            // 400: a wrong code. The simulator says how many attempts are left.
            SigningResult.Refused(messageOf(exception.message) ?: "Неверный код")
        } catch (exception: SourceUnavailableException) {
            if (exception.message?.contains("409") == true) SigningResult.Refused("Код больше не действует — запросите новый") else throw exception
        }

    private fun messageOf(text: String?): String? = text?.let { Regex("\"message\":\"([^\"]+)\"").find(it)?.groupValues?.get(1) }
}

@Component
class SimulatorCoreBanking(private val calls: ExternalCalls) : CoreBanking {
    private data class Loan(
        val contractNumber: String,
        val loanAccount: String,
        val settlementAccount: String,
        val disbursedAt: Instant,
        val maturityDate: LocalDate,
    )

    override fun disburse(request: LoanRequest): LoanBooked {
        val loan = calls.call("АБС") {
            it.post().uri("/ext/core/v1/loans").contentType(MediaType.APPLICATION_JSON)
                .body(
                    mapOf(
                        "applicationId" to request.applicationId,
                        "companyInn" to request.companyInn,
                        "amount" to request.amount,
                        "termMonths" to request.termMonths,
                        "annualRate" to request.annualRate,
                        "revolving" to request.revolving,
                        "signatureId" to request.signatureId,
                    ),
                ).retrieve().body(Loan::class.java)
        } ?: throw SourceUnavailableException("АБС", "АБС: пустой ответ")
        return LoanBooked(loan.contractNumber, loan.loanAccount, loan.settlementAccount, loan.disbursedAt, loan.maturityDate)
    }
}
