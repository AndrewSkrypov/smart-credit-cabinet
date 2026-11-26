package ru.creditcabinet.analytics.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.Acknowledgment
import org.springframework.stereotype.Component
import ru.creditcabinet.analytics.application.StatusUpdateResult
import ru.creditcabinet.analytics.application.UpdateStatusProjection
import java.util.concurrent.TimeUnit

@Component
@ConditionalOnProperty(prefix = "cabinet.analytics.kafka", name = ["enabled"], havingValue = "true")
class KafkaStatusIngest(
    private val projection: UpdateStatusProjection,
    private val kafka: KafkaTemplate<Any, Any>,
    private val mapper: ObjectMapper,
    private val metrics: MeterRegistry,
    @Value("\${spring.kafka.bootstrap-servers}") bootstrapServers: String,
    @Value("\${cabinet.analytics.kafka.topic}") sourceTopic: String,
    @Value("\${cabinet.analytics.kafka.group-id}") groupId: String,
    @Value("\${cabinet.analytics.kafka.dlq-topic}") private val deadLetterTopic: String,
) {
    private val decoder = StatusEventDecoder(mapper)
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(bootstrapServers.isNotBlank() && sourceTopic.isNotBlank() && groupId.isNotBlank() && deadLetterTopic.isNotBlank()) {
            "Analytics Kafka requires bootstrap servers, a consumer group, a source topic and a dead-letter topic"
        }
        require(sourceTopic != deadLetterTopic) { "Analytics Kafka requires distinct source and dead-letter topics" }
    }

    @KafkaListener(
        id = "analyticsApplicationStatus",
        topics = ["\${cabinet.analytics.kafka.topic}"],
        groupId = "\${cabinet.analytics.kafka.group-id}",
        containerFactory = "analyticsKafkaListenerContainerFactory",
    )
    fun ingest(record: ConsumerRecord<String, String>, acknowledgment: Acknowledgment) {
        val outcome = when (val decoded = decoder.decode(record.key(), record.value())) {
            is DecodedStatusEvent.Rejected -> quarantine(record, decoded.reason)
            is DecodedStatusEvent.Valid -> {
                when (val result = measureFailure("projection") { projection.applyEvent(decoded.event) }) {
                    StatusUpdateResult.VERSION_CONFLICT -> quarantine(record, StatusRejection.VERSION_CONFLICT)
                    StatusUpdateResult.EVENT_ID_CONFLICT -> quarantine(record, StatusRejection.EVENT_ID_CONFLICT)
                    else -> result.name
                }
            }
        }
        measureFailure("offset_commit", acknowledgment::acknowledge)
        metrics.counter("analytics.status.ingest", "outcome", outcome.lowercase()).increment()
    }

    private fun quarantine(record: ConsumerRecord<String, String>, reason: StatusRejection): String {
        val coordinate = "${record.topic()}:${record.partition()}:${record.offset()}"
        measureFailure("dead_letter") {
            val deadLetter = mapOf(
                "sourceTopic" to record.topic(),
                "sourcePartition" to record.partition(),
                "sourceOffset" to record.offset(),
                "reason" to reason.name,
            )
            kafka.send(deadLetterTopic, coordinate, mapper.writeValueAsString(deadLetter)).get(10, TimeUnit.SECONDS)
        }
        log.warn("Quarantined application status event at {} ({})", coordinate, reason)
        return reason.name
    }

    private inline fun <T> measureFailure(phase: String, action: () -> T): T {
        try {
            return action()
        } catch (exception: Exception) {
            metrics.counter("analytics.status.ingest.failures", "phase", phase).increment()
            if (exception is InterruptedException) Thread.currentThread().interrupt()
            throw exception
        }
    }
}
