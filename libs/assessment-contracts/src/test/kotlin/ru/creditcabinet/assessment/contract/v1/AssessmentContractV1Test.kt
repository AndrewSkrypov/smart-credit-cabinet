package ru.creditcabinet.assessment.contract.v1

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonMappingException
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import ru.creditcabinet.assessment.domain.SourceKind
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AssessmentContractV1Test {
    private val mapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)

    private val request = mapper.readValue<AssessmentRequestedV1>(fixture("assessment-requested"))
    private val assessed = mapper.readValue<AssessmentCompletedV1>(fixture("assessment-completed"))
    private val incomplete = mapper.readValue<AssessmentCompletedV1>(fixture("assessment-incomplete"))

    @Test
    fun `v1 request and both terminal outcomes round trip`() {
        assertRoundTrip("assessment-requested", request)
        assertRoundTrip("assessment-completed", assessed)
        assertRoundTrip("assessment-incomplete", incomplete)
        assessed.validateAgainst(request)
        incomplete.validateAgainst(request.copy(bureauConsent = null))
        assertEquals(request.cycle.candidatePreApprovalId, assessed.appliedPreApprovalId)
        assertNotNull(assessed.facts.complete())
        assertNull(incomplete.facts.complete())
        assertEquals(setOf(SourceKind.TAX, SourceKind.BUREAU), incomplete.missingSources)
    }

    @Test
    fun `incompatible versions and malformed terminal results fail JSON decoding`() {
        val unsupportedRequest = objectFixture("assessment-requested").put("protocolVersion", 2)
        val unsupportedResult = objectFixture("assessment-completed").put("protocolVersion", 2)
        val missingAssessment = objectFixture("assessment-completed").apply { remove("assessment") }
        val missingReceipt = objectFixture("assessment-completed").apply {
            (get("receipts") as ObjectNode).remove("BUREAU")
        }
        val mismatchedPolicy = objectFixture("assessment-completed").apply {
            (get("policy") as ObjectNode).put("kind", "REJECT")
        }

        assertFailsWith<JsonMappingException> { mapper.readValue<AssessmentRequestedV1>(unsupportedRequest.toString()) }
        for (result in listOf(unsupportedResult, missingAssessment, missingReceipt, mismatchedPolicy)) {
            assertFailsWith<JsonMappingException> { mapper.readValue<AssessmentCompletedV1>(result.toString()) }
        }
    }

    @Test
    fun `cycle identity includes the generation and all versioned references`() {
        val cycle = assessed.cycle
        val alternate = cycle.catalog.last()
        val differentCycles = listOf(
            cycle.copy(generation = "another-generation"),
            cycle.copy(cycleId = UUID.randomUUID()),
            cycle.copy(applicationId = UUID.randomUUID()),
            cycle.copy(applicationVersion = cycle.applicationVersion + 1),
            cycle.copy(ruleSetVersion = cycle.ruleSetVersion + 1),
            cycle.copy(selectedProduct = alternate),
            cycle.copy(catalog = cycle.catalog.reversed()),
            cycle.copy(catalog = cycle.catalog.dropLast(1) + alternate.copy(version = alternate.version + 1)),
            cycle.copy(candidatePreApprovalId = UUID.randomUUID()),
        )
        for (differentCycle in differentCycles) {
            assertFailsWith<IllegalArgumentException> {
                assessed.copy(cycle = differentCycle, appliedPreApprovalId = null).validateAgainst(request)
            }
        }
        assertFailsWith<IllegalArgumentException> { request.copy(catalog = request.catalog.reversed()) }
        assertFailsWith<IllegalArgumentException> { request.copy(rules = request.rules.copy(version = cycle.ruleSetVersion + 1)) }
    }

    @Test
    fun `consent and source receipts belong to this collection`() {
        assertFailsWith<IllegalArgumentException> { request.copy(bureauConsent = BureauConsentV1(UUID.randomUUID())) }
        assertFailsWith<IllegalArgumentException> { assessed.validateAgainst(request.copy(bureauConsent = null)) }
        val lateRegistryReceipt = incomplete.receipts + (SourceKind.REGISTRY to request.deadline)
        assertFailsWith<IllegalArgumentException> { incomplete.copy(receipts = lateRegistryReceipt).validateAgainst(request) }
        val incorrectBankReceipt = incomplete.receipts + (SourceKind.BANK to request.startsAt)
        assertFailsWith<IllegalArgumentException> { incomplete.copy(receipts = incorrectBankReceipt).validateAgainst(request) }
        val changedRegistry = assertNotNull(incomplete.facts.registry).copy(inn = "000000000001")
        assertFailsWith<IllegalArgumentException> { incomplete.copy(facts = incomplete.facts.copy(registry = changedRegistry)).validateAgainst(request) }
    }

    @Test
    fun `preapproval and offer terms must match frozen input`() {
        val candidate = assertNotNull(request.candidatePreApproval)
        val policy = assertNotNull(assessed.policy)
        val terms = assertNotNull(policy.terms)
        val expired = candidate.copy(validUntil = assessed.completedAt)
        assertFailsWith<IllegalArgumentException> { assessed.validateAgainst(request.copy(candidatePreApproval = expired)) }
        assertFailsWith<IllegalArgumentException> { assessed.validateAgainst(request.copy(candidatePreApproval = null)) }
        assertFailsWith<IllegalArgumentException> {
            assessed.copy(policy = policy.copy(terms = terms.copy(rate = terms.rate + 1.toBigDecimal()))).validateAgainst(request)
        }
        assertFailsWith<IllegalArgumentException> {
            assessed.copy(policy = policy.copy(terms = terms.copy(amount = terms.amount + 1)), appliedPreApprovalId = null).validateAgainst(request)
        }

        val alternate = request.catalog.last()
        val counter = PolicyV1(PolicyKindV1.COUNTER_OFFER, terms.copy(productCode = alternate.ref.code, amount = alternate.maxAmount + 1))
        val manual = PolicyV1(PolicyKindV1.MANUAL_REVIEW, manualReasons = listOf(ManualReasonV1.RATING_C), recommendation = counter)
        assertFailsWith<IllegalArgumentException> {
            assessed.copy(policy = manual, appliedPreApprovalId = null).validateAgainst(request)
        }
    }

    @Test
    fun `partial collection cannot carry a decision or claim every source arrived`() {
        assertFailsWith<IllegalArgumentException> { incomplete.copy(assessment = assessed.assessment) }
        assertFailsWith<IllegalArgumentException> { incomplete.copy(missingSources = emptySet()) }
        assertFailsWith<IllegalArgumentException> { incomplete.copy(completedAt = Instant.EPOCH) }
    }

    private fun assertRoundTrip(name: String, value: Any) {
        val expected = objectFixture(name)
        val actual = mapper.readTree(mapper.writeValueAsString(value)) as ObjectNode
        assertEquals(expected.remove("missingSources")?.toSet(), actual.remove("missingSources")?.toSet())
        assertEquals(expected, actual)
    }

    private fun objectFixture(name: String) = mapper.readTree(fixture(name)) as ObjectNode

    private fun fixture(name: String) = Path.of("../../contracts/events/credit/assessment/fixtures/$name.json").readText()
}
