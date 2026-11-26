package ru.creditcabinet.analytics.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.mockito.Mockito
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.kafka.support.SendResult
import ru.creditcabinet.analytics.application.StatusUpdateResult
import ru.creditcabinet.analytics.application.UpdateStatusProjection
import ru.creditcabinet.analytics.domain.ApplicationStatus
import ru.creditcabinet.analytics.domain.StatusEvent
import ru.creditcabinet.analytics.domain.VersionedStatus
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KafkaStatusIngestTest {
    private val mapper = ObjectMapper()
    private val projection = Mockito.mock(UpdateStatusProjection::class.java)

    @Suppress("UNCHECKED_CAST")
    private val kafka = Mockito.mock(KafkaTemplate::class.java) as KafkaTemplate<Any, Any>

    @Suppress("UNCHECKED_CAST")
    private val send = Mockito.mock(CompletableFuture::class.java) as CompletableFuture<SendResult<Any, Any>>

    private val metrics = SimpleMeterRegistry()
    private val acknowledgment = Mockito.mock(Acknowledgment::class.java)
    private val ingest = KafkaStatusIngest(projection, kafka, mapper, metrics, "localhost:9092", topic, "analytics-test", dlq)

    @Test
    fun `acknowledges only after the projection completes`() {
        Mockito.`when`(projection.applyEvent(event)).thenReturn(StatusUpdateResult.APPLIED)

        ingest.ingest(record(payload), acknowledgment)

        val order = Mockito.inOrder(projection, acknowledgment)
        order.verify(projection).applyEvent(event)
        order.verify(acknowledgment).acknowledge()
        Mockito.verifyNoInteractions(kafka)
        assertEquals(1.0, metrics.counter("analytics.status.ingest", "outcome", "applied").count())
    }

    @Test
    fun `leaves the offset pending when the database fails`() {
        Mockito.`when`(projection.applyEvent(event)).thenThrow(IllegalStateException("database unavailable"))

        assertFailsWith<IllegalStateException> { ingest.ingest(record(payload), acknowledgment) }

        Mockito.verifyNoInteractions(acknowledgment, kafka)
        assertEquals(1.0, metrics.counter("analytics.status.ingest.failures", "phase", "projection").count())
    }

    @Test
    fun `publishes only source coordinates and reason before acknowledging a malformed event`() {
        val original = """{"privateField":"SYNTHETIC_PRIVATE_MARKER"}"""
        val deadLetter = """{"sourceTopic":"$topic","sourcePartition":2,"sourceOffset":17,"reason":"INVALID_PAYLOAD"}"""
        Mockito.`when`(kafka.send(dlq, coordinate, deadLetter)).thenReturn(send)

        ingest.ingest(record(original), acknowledgment)

        Mockito.verifyNoInteractions(projection)
        val order = Mockito.inOrder(kafka, send, acknowledgment)
        order.verify(kafka).send(dlq, coordinate, deadLetter)
        order.verify(send).get(10, TimeUnit.SECONDS)
        order.verify(acknowledgment).acknowledge()
        assertEquals(1.0, metrics.counter("analytics.status.ingest", "outcome", "invalid_payload").count())
    }

    @Test
    fun `preserves the source offset until a conflicting event reaches the dead-letter topic`() {
        val deadLetter = """{"sourceTopic":"$topic","sourcePartition":2,"sourceOffset":17,"reason":"VERSION_CONFLICT"}"""
        Mockito.`when`(projection.applyEvent(event)).thenReturn(StatusUpdateResult.VERSION_CONFLICT)
        Mockito.`when`(kafka.send(dlq, coordinate, deadLetter)).thenReturn(send)
        Mockito.`when`(send.get(10, TimeUnit.SECONDS)).thenThrow(ExecutionException(IllegalStateException("broker unavailable")))

        assertFailsWith<ExecutionException> { ingest.ingest(record(payload), acknowledgment) }

        Mockito.verifyNoInteractions(acknowledgment)
        assertEquals(1.0, metrics.counter("analytics.status.ingest.failures", "phase", "dead_letter").count())
    }

    @Test
    fun `recognizes v1 wire values and rejects coercions or extra fields`() {
        val decoder = StatusEventDecoder(mapper)
        assertEquals(DecodedStatusEvent.Valid(event), decoder.decode(applicationId, payload))
        assertEquals(
            DecodedStatusEvent.Valid(event.copy(state = event.state.copy(applicationVersion = Long.MAX_VALUE))),
            decoder.decode(applicationId, payload.replace(":7,", ":${Long.MAX_VALUE},")),
        )
        val invalid = listOf(
            payload.replace(":7,", ":\"7\","),
            payload.replace(":7,", ":7.5,"),
            payload.replace(":7,", ":-1,"),
            payload.replace(":7,", ":9223372036854775808,"),
            payload.replace("SUBMITTED", "UNKNOWN"),
            payload.replace("2026-11-26", "2026-02-29"),
            payload.replace("12:15:11.123456789Z", "15:15:11.123456789+03:00"),
            payload.replace(applicationId, applicationId.uppercase()),
            payload.replace("\"applicationVersion\":7,", ""),
            payload.replace("{", "{\"status\":\"SUBMITTED\","),
            payload.replace("{", "{\"companyId\":\"private\","),
            "$payload{}",
            null,
        )
        for (value in invalid) {
            assertEquals(DecodedStatusEvent.Rejected(StatusRejection.INVALID_PAYLOAD), decoder.decode(applicationId, value))
        }
        for (key in listOf(null, "", applicationId.uppercase(), eventId)) {
            assertEquals(DecodedStatusEvent.Rejected(StatusRejection.KEY_MISMATCH), decoder.decode(key, payload))
        }
    }

    private fun record(value: String) = ConsumerRecord(topic, 2, 17, applicationId, value)

    private companion object {
        const val topic = "alpha.test.credit.application-status-changed.v1.g001"
        const val dlq = "$topic.dlq"
        const val coordinate = "$topic:2:17"
        const val applicationId = "a4fd57ac-6182-4647-bdd2-176221bab0c2"
        const val eventId = "32bb5ccb-40b9-41eb-a75e-715360e17517"
        val payload = """
            {"eventId":"$eventId","applicationId":"$applicationId","applicationVersion":7,
            "status":"SUBMITTED","occurredAt":"2026-11-26T12:15:11.123456789Z"}
        """.trimIndent()
        val event = StatusEvent(
            UUID.fromString(eventId),
            VersionedStatus(UUID.fromString(applicationId), 7, ApplicationStatus.SUBMITTED),
            Instant.parse("2026-11-26T12:15:11.123456789Z"),
        )
    }
}
