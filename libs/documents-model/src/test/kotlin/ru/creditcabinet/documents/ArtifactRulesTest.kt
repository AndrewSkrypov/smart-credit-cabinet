package ru.creditcabinet.documents

import ru.creditcabinet.document.generated.ArtifactInput
import ru.creditcabinet.document.generated.ArtifactKind
import ru.creditcabinet.document.generated.ArtifactPhase
import ru.creditcabinet.product.domain.LoanMath
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ArtifactRulesTest {
    private val createdAt = Instant.parse("2026-11-27T00:00:00Z")
    private val input = ArtifactInput(
        UUID.randomUUID(), 123, UUID.randomUUID(), "Заёмщик", "1234567890", "Оборотный",
        123456, 12, LoanMath.calculate(123456, BigDecimal("12.00"), 12, false),
        ArtifactPhase.FINAL, "Зафиксированный текст договора", null, false, createdAt, "C-123", createdAt,
    ).let { it.copy(contractTextHash = sha256(it.contractText.toByteArray())) }

    @Test
    fun `frozen final input validates each artifact kind without changing terms`() {
        for (kind in ArtifactKind.entries) {
            val record = artifact(kind)
            ArtifactRules.validate(record, input, 1)
            ArtifactRules.validate(record.copy(backend = "S3"), input, 1)
            assertEquals(BigDecimal("12.00"), input.calculation.rate)
            assertEquals(123456L, input.amount)
            assertEquals(sha256(record.inputJson.toByteArray()), record.inputHash)
        }
    }

    @Test
    fun `changed digest scope offer phase or template cannot reuse an artifact identity`() {
        val record = artifact()
        val incompatible = listOf(
            record.copy(inputHash = "0".repeat(64)),
            record.copy(inputJson = "{}"),
            record.copy(scope = record.scope.copy(applicationId = UUID.randomUUID())),
            record.copy(offerId = UUID.randomUUID()),
            record.copy(phase = "PREVIEW"),
            record.copy(templateVersion = 2),
        )
        for (changed in incompatible) {
            assertFailsWith<IllegalArgumentException> { ArtifactRules.validate(changed, input, 1) }
        }
    }

    @Test
    fun `artifact placement cannot masquerade as a completed file or another format`() {
        val record = artifact()
        val incompatible = listOf(
            record.copy(status = "READY"),
            record.copy(sizeBytes = 100),
            record.copy(checksum = "a".repeat(64)),
            record.copy(storedName = "generated-${UUID.randomUUID()}.pdf"),
            record.copy(contentType = "text/plain"),
            record.copy(fileName = "contract.xlsx"),
            record.copy(backend = "PUBLIC"),
        )
        for (changed in incompatible) {
            assertFailsWith<IllegalArgumentException> { ArtifactRules.validate(changed, input, 1) }
        }
    }

    @Test
    fun `contract requires final frozen text or an explicit historical summary`() {
        val record = artifact()
        assertFailsWith<IllegalArgumentException> {
            ArtifactRules.validate(record.copy(phase = "PREVIEW"), input.copy(phase = ArtifactPhase.PREVIEW), 1)
        }
        assertFailsWith<IllegalArgumentException> {
            ArtifactRules.validate(record, input.copy(contractTextHash = null), 1)
        }
        assertFailsWith<IllegalArgumentException> {
            ArtifactRules.validate(record, input.copy(contractText = "Изменённый договор"), 1)
        }
        ArtifactRules.validate(record, input.copy(contractTextHash = null, legacySummary = true), 1)
    }

    @Test
    fun `empty or oversized schedules cannot be published`() {
        val record = artifact()
        for (schedule in listOf(emptyList(), List(601) { input.calculation.schedule.first() })) {
            assertFailsWith<IllegalArgumentException> {
                ArtifactRules.validate(record, input.copy(calculation = input.calculation.copy(schedule = schedule)), 1)
            }
        }
    }

    private fun artifact(kind: ArtifactKind = ArtifactKind.CONTRACT_PDF): ArtifactRecord {
        val id = UUID.randomUUID()
        // The inbound adapter supplies the canonical JSON; these rules keep its bytes and digest unchanged.
        val frozenJson = """{"amount":123456,"rate":12.00}"""
        return ArtifactRecord(
            id, DocumentScope(UUID.randomUUID(), input.applicationId), input.offerId,
            kind.name, "FINAL", 1, sha256(frozenJson.toByteArray()), frozenJson,
            "Договор", "artifact.${kind.extension}", kind.contentType, "generated-$id.${kind.extension}", "LOCAL", createdAt,
        )
    }
}
