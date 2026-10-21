package ru.creditcabinet.pipeline

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import ru.creditcabinet.assessment.domain.BankInfo
import ru.creditcabinet.assessment.domain.BureauInfo
import ru.creditcabinet.assessment.domain.BureauObligation
import ru.creditcabinet.assessment.domain.RegistryInfo
import ru.creditcabinet.assessment.domain.SourceData
import ru.creditcabinet.assessment.domain.SourceKind
import ru.creditcabinet.assessment.domain.TaxInfo
import ru.creditcabinet.integration.CompanySources
import ru.creditcabinet.integration.CoreBanking
import ru.creditcabinet.integration.LoanBooked
import ru.creditcabinet.integration.LoanRequest
import ru.creditcabinet.integration.SigningGateway
import ru.creditcabinet.integration.SigningResult
import ru.creditcabinet.integration.SigningStarted
import ru.creditcabinet.integration.SourceUnavailableException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A clock the test moves forward to reach deadlines without waiting. */
class MutableClock(var now: Instant) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now

    fun advance(duration: Duration) {
        now = now.plus(duration)
    }
}

/** The external systems as the test wants them: per-ИНН data and sources that fail on demand. */
class FakeSources : CompanySources {
    val companies = ConcurrentHashMap<String, SourceData>()
    val down: MutableSet<SourceKind> = ConcurrentHashMap.newKeySet()

    private fun data(inn: String, kind: SourceKind): SourceData {
        if (kind in down) throw SourceUnavailableException(kind.title, "${kind.title}: недоступен")
        return companies.getValue(inn)
    }

    override fun registry(inn: String) = data(inn, SourceKind.REGISTRY).registry

    override fun tax(inn: String) = data(inn, SourceKind.TAX).tax

    override fun bureau(inn: String, consentId: String) = data(inn, SourceKind.BUREAU).bureau

    override fun bank(inn: String) = data(inn, SourceKind.BANK).bank

    companion object {
        /** A healthy business: registered 6 years ago, revenue 14.4 million, low debt, a bank client with even turnover. */
        fun healthy(
            inn: String,
            today: LocalDate,
            status: String = "ACTIVE",
            okved: String = "56.10",
            client: Boolean = true,
            overdueDays: Int = 0,
        ) = SourceData(
            registry = RegistryInfo(
                inn, "315000000000206", "ИП Тест", "ИП Тест", status, status,
                today.minusMonths(
                    72,
                ),
                okved, "Кафе", "Тест", "г. Тест", false, false,
            ),
            tax = TaxInfo(true, today.year - 1, 14_400_000, 12_600_000, 0, 6, "USN", false),
            bureau = BureauInfo(listOf(BureauObligation("Банк «Пример»", "LOAN", 900_000, 520_000, 30_000, overdueDays)), 30_000, overdueDays, overdueDays, 1),
            bank = BankInfo(client, if (client) "40702810000000000001" else null, if (client) List(12) { 1_080_000L + (it % 2) * 100_000 } else emptyList()),
        )
    }
}

class FakeSigning(private val clock: Clock) : SigningGateway {
    val code = "424242"

    override fun start(documentId: String, title: String, text: String, signerName: String): SigningStarted =
        SigningStarted(UUID.randomUUID(), code, clock.instant().plus(Duration.ofMinutes(5)))

    override fun confirm(sessionId: UUID, code: String): SigningResult =
        if (code == this.code) SigningResult.Signed(UUID.randomUUID(), Instant.now()) else SigningResult.Refused("Неверный код, осталось попыток: 4")
}

class FakeCore : CoreBanking {
    var down = false
    private val loans = ConcurrentHashMap<UUID, LoanBooked>()

    override fun disburse(request: LoanRequest): LoanBooked {
        if (down) throw SourceUnavailableException("АБС", "АБС: недоступна")
        return loans.computeIfAbsent(request.applicationId) {
            LoanBooked(
                "КД-2026-" + request.applicationId.toString().take(8),
                "45206810000000000001",
                "40702810000000000001",
                Instant.now(),
                LocalDate.now().plusMonths(request.termMonths.toLong()),
            )
        }
    }
}

@TestConfiguration
class PipelineFakesConfig {
    @Bean
    @Primary
    fun mutableClock() = MutableClock(Instant.parse("2026-10-21T09:00:00Z"))

    @Bean
    @Primary
    fun fakeSources() = FakeSources()

    @Bean
    @Primary
    fun fakeSigning(clock: MutableClock) = FakeSigning(clock)

    @Bean
    @Primary
    fun fakeCore() = FakeCore()
}
