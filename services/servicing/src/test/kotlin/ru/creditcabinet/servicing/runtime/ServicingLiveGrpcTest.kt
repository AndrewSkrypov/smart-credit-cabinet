@file:Suppress("ktlint:standard:no-wildcard-imports")

package ru.creditcabinet.servicing.runtime

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import ru.creditcabinet.common.exception.ForbiddenException
import ru.creditcabinet.common.exception.UnauthorizedException
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.servicing.contract.v1.*
import ru.creditcabinet.servicing.domain.*
import ru.creditcabinet.servicing.grpc.*
import ru.creditcabinet.servicing.paymentreserve.domain.*
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import java.util.UUID
import kotlin.test.*

/** Connects to the independently launched candidate bootJar over real mTLS and PostgreSQL. */
@EnabledIfEnvironmentVariable(named = "ALPHA_SERVICING_LIVE_PHASE", matches = "open|replay")
class ServicingLiveGrpcTest {
    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    private val directory = Path.of(System.getenv("ALPHA_SERVICING_LIVE_ARTIFACTS"))
    private val tls = Path.of(System.getenv("ALPHA_SERVICING_LIVE_TLS"))
    private val port = System.getenv("ALPHA_SERVICING_LIVE_PORT").toInt()
    private var authorization: String? = null
    private fun token(role: String, company: UUID?, user: UUID = UUID.randomUUID()): String = "Bearer " + Jwts.builder().subject(user.toString())
        .claim(TokenClaims.ROLE, role).claim(TokenClaims.COMPANY_ID, company?.toString()).issuedAt(Date.from(Instant.now()))
        .expiration(
            Date.from(Instant.now().plusSeconds(600)),
        ).signWith(Keys.hmacShaKeyFor(System.getenv("ALPHA_SERVICING_TEST_JWT_SECRET").toByteArray())).compact()

    @Test fun `independent runtime preserves opening receipt balance and authorization across restart`() {
        val channel =
            servicingTlsChannel("localhost", port, tls.resolve("credit.crt").toFile(), tls.resolve("credit.key").toFile(), tls.resolve("ca.crt").toFile())
        val client = ServicingGrpcClient(channel, mapper, { authorization })
        try {
            if (System.getenv("ALPHA_SERVICING_LIVE_PHASE") == "replay") {
                val command = mapper.readValue(directory.resolve("command.json").toFile(), OpenLoanCommand::class.java)
                val receipt = mapper.readValue(directory.resolve("loan-opening-receipt.json").toFile(), LoanOpenedReceipt::class.java)
                assertEquals(receipt, client.open(command))
                authorization = token(Roles.CLIENT, command.companyId)
                val current = assertNotNull(client.byApplication(command.applicationId))
                assertEquals(receipt.accountId, current.id)
                assertEquals(2, current.tranches.size)
                assertEquals(630_000L, current.disbursedAmount)
                assertEquals(130_000L, client.reserve(current.id)!!.drawnAmount)
                val latestPayment = mapper.readValue(directory.resolve("latest-payment-account.json").toFile(), LoanAccount::class.java)
                assertEquals(latestPayment, current)
                assertTrue(current.payments.any { it.status == LoanPaymentStatus.PAID })
                return
            }
            val now = Instant.now()
            val today = now.atZone(ZoneId.of("Europe/Moscow")).toLocalDate()
            val command = OpenLoanCommand(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 9, UUID.randomUUID(), UUID.randomUUID(), "c".repeat(64),
                "LIVE-${UUID.randomUUID().toString().take(8)}", 1_000_000, 12, BigDecimal("20.00"), true, now, today.plusMonths(12),
            )
            val receipt = client.open(command)
            assertEquals(receipt, client.open(command))
            mapper.writeValue(directory.resolve("command.json").toFile(), command)
            mapper.writeValue(directory.resolve("loan-opening-receipt.json").toFile(), receipt)
            assertFailsWith<UnauthorizedException> { client.byApplication(command.applicationId) }
            val actor = ServicingActor(UUID.randomUUID(), command.companyId)
            authorization = token(Roles.CLIENT, command.companyId, actor.userId)
            val account = assertNotNull(client.byApplication(command.applicationId))
            assertEquals(500_000L, account.disbursedAmount)
            assertEquals(12, account.payments.size)
            assertEquals(BigDecimal("500000.00"), account.outstandingPrincipal)
            assertFailsWith<ServicingConflict> { client.open(command.copy(rate = BigDecimal("21.00"))) }
            val input = PaymentReserveInputs(20_000, 180_000, 100_000, 50_000, today.plusDays(1), today.plusDays(5))
            client.putReserve(account.id, ReserveCommand(actor, input))
            val quote = client.receipt(account.id, ReceiptCommand(actor, ReceiptEvent("live-delay", ReceiptStatus.DELAYED)))
            assertEquals(130_000L, quote.suggestedDraw)
            val confirmed = client.confirmReserve(account.id, ConfirmReserveCommand(actor, quote.version))
            assertEquals(130_000L, confirmed.drawnAmount)
            assertEquals(confirmed, client.confirmReserve(account.id, ConfirmReserveCommand(actor, quote.version)))
            assertFailsWith<ForbiddenException> { client.draw(account.id, DrawCommand(actor.copy(userId = UUID.randomUUID()), 1_000)) }
            authorization = token(Roles.CLIENT, UUID.randomUUID())
            assertFailsWith<ServicingNotFound> { client.byApplication(command.applicationId) }
            authorization = token(Roles.AGENT, null)
            val summary = assertNotNull(client.agentSummary(command.applicationId))
            assertEquals(AgentReserveAction.TRANCHE_CONFIRMED, summary.action)
            assertFailsWith<ForbiddenException> { client.draw(account.id, DrawCommand(actor, 1_000)) }
            assertFailsWith<ForbiddenException> { client.reserve(account.id) }
            // A certificate signed by the same CA but with the wrong peer identity is rejected.
            val wrong =
                servicingTlsChannel("localhost", port, tls.resolve("other.crt").toFile(), tls.resolve("other.key").toFile(), tls.resolve("ca.crt").toFile())
            try {
                assertFailsWith<ForbiddenException> {
                    ServicingGrpcClient(wrong, mapper, { null }).open(command.copy(applicationId = UUID.randomUUID(), commandId = UUID.randomUUID()))
                }
            } finally {
                wrong.shutdownNow()
            }
            authorization = token(Roles.CLIENT, command.companyId, actor.userId)
            val beforeOutage = client.byApplication(command.applicationId)
            val unavailablePort = java.net.ServerSocket(0).use { it.localPort }
            val unavailable =
                servicingTlsChannel(
                    "localhost",
                    unavailablePort,
                    tls.resolve("credit.crt").toFile(),
                    tls.resolve("credit.key").toFile(),
                    tls.resolve("ca.crt").toFile(),
                )
            try {
                val failed = ServicingGrpcClient(unavailable, mapper, { authorization }, 500)
                assertFailsWith<ServicingUnavailable> { failed.byId(account.id) }
                assertFailsWith<ServicingUnavailable> { failed.draw(account.id, DrawCommand(actor, 1_000)) }
            } finally {
                unavailable.shutdownNow()
            }
            assertEquals(beforeOutage, client.byApplication(command.applicationId))
            val paid = client.payNext(account.id, PayNextCommand(actor))
            assertTrue(paid.outstandingPrincipal < BigDecimal("630000.00"))
            mapper.writeValue(directory.resolve("latest-payment-account.json").toFile(), client.byApplication(command.applicationId))
            val legacy = mapper.readValue(directory.resolve("legacy-account.json").toFile(), LoanAccount::class.java)
            val legacyActor = ServicingActor(UUID.randomUUID(), legacy.companyId)
            authorization = token(Roles.CLIENT, legacy.companyId, legacyActor.userId)
            val oldSchedule = assertNotNull(client.byApplication(legacy.applicationId))
            val localCommand = mapper.readValue(directory.resolve("local-operation-command.json").toFile(), DrawCommand::class.java)
            val localResult = mapper.readValue(directory.resolve("local-operation-result.json").toFile(), LoanAccount::class.java)
            authorization = token(Roles.CLIENT, legacy.companyId, localCommand.actor.userId)
            assertEquals(localResult, client.draw(legacy.id, localCommand))
            assertEquals(oldSchedule, client.byApplication(legacy.applicationId))
            val remoteCommand = DrawCommand(localCommand.actor, 50_000, "Reschedule before reverse acceptance", UUID.randomUUID())
            val drawn = client.draw(legacy.id, remoteCommand)
            assertEquals(drawn, client.draw(legacy.id, remoteCommand))
            assertEquals(560_000L, drawn.disbursedAmount)
            assertTrue(drawn.payments.filter { it.status == LoanPaymentStatus.SCHEDULED }.none { row -> oldSchedule.payments.any { it.id == row.id } })
            mapper.writeValue(directory.resolve("legacy-after-draw.json").toFile(), client.byApplication(legacy.applicationId))
            mapper.writeValue(directory.resolve("remote-operation-command.json").toFile(), remoteCommand)
            mapper.writeValue(directory.resolve("remote-operation-result.json").toFile(), drawn)
        } finally {
            channel.shutdownNow()
        }
    }
}
