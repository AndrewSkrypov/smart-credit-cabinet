package ru.creditcabinet.application.infrastructure

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.apache.kafka.clients.producer.MockProducer
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.errors.TimeoutException
import org.apache.kafka.common.serialization.StringSerializer
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class KafkaApplicationStatusPublisherTest {
    @Test
    fun `publishes the outbox payload with application id as the Kafka key`() {
        val producer = MockProducer(true, StringSerializer(), StringSerializer())
        KafkaApplicationStatusPublisher(TOPIC, jacksonObjectMapper()) { producer }.use { it.deliver(payload) }

        val record = producer.history().single()
        assertEquals(TOPIC, record.topic())
        assertEquals(APPLICATION_ID, record.key())
        assertEquals(payload, record.value())
    }

    @Test
    fun `propagates broker acknowledgement failure for outbox retry`() {
        val producer = mock<Producer<String, String>>()
        val ack = mock<Future<RecordMetadata>>()
        val failure = TimeoutException("broker acknowledgement timed out")
        whenever(producer.send(any<ProducerRecord<String, String>>())).thenReturn(ack)
        whenever(ack.get()).thenThrow(ExecutionException(failure))

        KafkaApplicationStatusPublisher(TOPIC, jacksonObjectMapper()) { producer }.use {
            assertSame(failure, assertFailsWith<TimeoutException> { it.deliver(payload) })
        }
    }

    private companion object {
        const val TOPIC = "credit.application-status.v1"
        const val APPLICATION_ID = "b327875a-0212-442a-9860-51bc34838231"
        val payload = """
            {"eventId":"38ce1c24-d728-4f60-961c-f6d6c7cad7a4","applicationId":"$APPLICATION_ID",
            "applicationVersion":1,"status":"SUBMITTED","occurredAt":"2026-11-26T12:00:00Z"}
        """.trimIndent()
    }
}
