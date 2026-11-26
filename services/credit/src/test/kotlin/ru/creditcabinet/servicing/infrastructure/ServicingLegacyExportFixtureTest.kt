@file:Suppress("ktlint:standard:no-wildcard-imports")

package ru.creditcabinet.servicing.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import ru.creditcabinet.application.domain.*
import ru.creditcabinet.offer.Loan
import ru.creditcabinet.pipeline.PipelineFakesConfig
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.servicing.application.LoanServicingService
import ru.creditcabinet.servicing.contract.v1.*
import ru.creditcabinet.servicing.paymentreserve.domain.*
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Clock
import java.time.ZoneId
import java.util.UUID
import kotlin.test.*

/** Leaves an actual local-domain aggregate for the isolated cutover harness, not a production seed. */
@SpringBootTest
@Import(PipelineFakesConfig::class)
@EnabledIfEnvironmentVariable(named="ALPHA_SERVICING_FIXTURE_DIR",matches=".+")
class ServicingLegacyExportFixtureTest {
    @Autowired private lateinit var applications: ApplicationRepository
    @Autowired private lateinit var opening: LoanServicingService
    @Autowired private lateinit var operations: ServicingOperations
    @Autowired private lateinit var mapper: ObjectMapper
    @Autowired private lateinit var clock: Clock

    @Test fun `legacy aggregate with reserve events and full schedule is available for fenced export`() {
        val actor = ServicingActor(UUID.randomUUID(),UUID.randomUUID())
        val today = clock.instant().atZone(ZoneId.of("Europe/Moscow")).toLocalDate()
        val app = applications.save(LoanApplication(company=CompanySnapshot(actor.companyId,"0012345678","Legacy fixture",LegalForm.LEGAL_ENTITY),createdBy=actor.userId,
            terms=ApplicationTerms("OVERDRAFT",FinancingPurpose.CASH_GAP,amount=1_000_000,termMonths=12),status=ApplicationStatus.DISBURSED,
            createdAt=clock.instant(),submittedAt=clock.instant(),productCatalogSnapshot=mapOf("OVERDRAFT" to 1L)))
        val account = opening.openOnDisbursement(app,Loan(app.id,"LEGACY-${app.id.toString().take(8)}","45206810000000000001","40702810000000000001",1_000_000,12,BigDecimal("20.00"),true,clock.instant(),today.plusMonths(12)))
        operations.putReserve(account.id,ReserveCommand(actor,PaymentReserveInputs(20_000,180_000,100_000,50_000,today.plusDays(1),today.plusDays(5))))
        val quote = operations.receipt(account.id,ReceiptCommand(actor,ReceiptEvent("legacy-delayed",ReceiptStatus.DELAYED)))
        assertEquals(130_000L,quote.requiredAmount)
        assertEquals(12,account.payments.size)
        mapper.writeValue(Path.of(System.getenv("ALPHA_SERVICING_FIXTURE_DIR"),"legacy-account.json").toFile(),account)
    }
}
