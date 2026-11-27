package ru.creditcabinet.application.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.producer.Producer
import org.apache.kafka.clients.producer.ProducerRecord
import ru.creditcabinet.common.outbox.DeliveryHandler
import java.time.Duration
import java.util.concurrent.ExecutionException

internal class KafkaApplicationStatusPublisher(
    private val topic: String,
    private val mapper: ObjectMapper,
    producerFactory: () -> Producer<String, String>,
) : DeliveryHandler, AutoCloseable {
    override val channel = APPLICATION_STATUS_CHANNEL

    // Broker DNS and connection failures belong to the worker's retry path.
    private val producer = lazy(producerFactory)

    override fun deliver(payload: String) {
        val key = mapper.readTree(payload).required("applicationId").asText()
        try {
            producer.value.send(ProducerRecord(topic, key, payload)).get()
        } catch (exception: ExecutionException) {
            throw (exception.cause as? Exception ?: exception)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            throw exception
        }
    }

    override fun close() {
        if (producer.isInitialized()) producer.value.close(Duration.ofSeconds(2))
    }
}
