package ru.creditcabinet.servicing.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.junit.jupiter.api.Test
import ru.creditcabinet.servicing.domain.LoanAccount
import ru.creditcabinet.servicing.grpc.ServicingWire
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import ru.creditcabinet.contracts.servicing.v1.LoanAccount as WireAccount

class ServicingWireTest {
    @Test fun `protobuf decimal strings preserve monetary and rate scale including nested schedules`() {
        val codec = ServicingWire(ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule()))
        val account = LoanAccount.open(UUID.randomUUID(), UUID.randomUUID(), "WIRE-1", 1_000_000, 12,
            BigDecimal("20.00"), true, Instant.parse("2026-10-01T10:00:00Z"), LocalDate.parse("2027-10-01"), LocalDate.parse("2026-10-01"))
        val wire = codec.encode<WireAccount>(account, WireAccount.newBuilder())
        assertEquals("500000.00", wire.outstandingPrincipal)
        assertEquals("20.00", wire.rate)
        assertEquals(account, codec.decode(wire, LoanAccount::class.java))
    }
}
