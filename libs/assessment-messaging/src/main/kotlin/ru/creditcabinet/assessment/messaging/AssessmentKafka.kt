package ru.creditcabinet.assessment.messaging

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import java.time.Clock
import java.time.Duration
import java.util.concurrent.TimeUnit

/** One instance per direction on each side; JDBC failures leave the record at its current offset. */
class AssessmentKafka(
    private val settings: AssessmentKafkaSettings,
    private val outbox: AssessmentOutbox,
    private val sendingTopic: String,
    private val receivingTopic: String,
    consumerName: String,
    private val clock: Clock,
    private val receive: (key: String, payload: String) -> Unit,
) : AutoCloseable {
    private val producer = lazy {
        KafkaProducer(
            mapOf(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to settings.bootstrapServers,
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
                ProducerConfig.ACKS_CONFIG to "all",
                ProducerConfig.MAX_BLOCK_MS_CONFIG to 3000,
                ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to 4000,
                ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to 6000,
            ),
            StringSerializer(),
            StringSerializer(),
        )
    }

    private val consumer = lazy {
        KafkaConsumer(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to settings.bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG to "alpha.${settings.stream}.assessment.v1.$consumerName",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG to false,
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG to 1,
                ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG to 4000,
            ),
            StringDeserializer(),
            StringDeserializer(),
        ).also { it.subscribe(listOf(receivingTopic)) }
    }

    @Scheduled(fixedDelayString = "PT0.25S", scheduler = "assessmentPublishScheduler")
    @Synchronized
    fun publish() {
        val claim = outbox.claim(settings.stream, clock.instant()) ?: return
        val error = try {
            producer.value.send(ProducerRecord(sendingTopic, claim.applicationId.toString(), claim.payload)).get(7, TimeUnit.SECONDS)
            null
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            "InterruptedException"
        } catch (exception: Exception) {
            exception.javaClass.simpleName
        }
        outbox.finish(claim, clock.instant(), error)
        if (error != null) log.warn("Assessment outbox publish failed on {} ({})", sendingTopic, error)
    }

    @Scheduled(fixedDelayString = "PT0.25S", scheduler = "assessmentConsumeScheduler")
    fun consume() = synchronized(consumer) {
        val client = consumer.value
        for (record in client.poll(Duration.ofMillis(250))) {
            val partition = TopicPartition(record.topic(), record.partition())
            try {
                try {
                    val key = record.key() ?: throw InvalidAssessmentEnvelope()
                    val payload = record.value() ?: throw InvalidAssessmentEnvelope()
                    receive(key, payload)
                } catch (_: InvalidAssessmentEnvelope) {
                    log.warn("Rejected assessment envelope on {}", receivingTopic)
                }
                client.commitSync(mapOf(partition to OffsetAndMetadata(record.offset() + 1)))
            } catch (exception: Exception) {
                if (partition in client.assignment()) client.seek(partition, record.offset())
                log.warn("Assessment result persistence will be retried ({})", exception.javaClass.simpleName)
            }
        }
    }

    @Synchronized
    override fun close() {
        synchronized(consumer) {
            if (consumer.isInitialized()) consumer.value.close(Duration.ofSeconds(2))
        }
        if (producer.isInitialized()) producer.value.close(Duration.ofSeconds(2))
    }

    private companion object {
        val log = LoggerFactory.getLogger(AssessmentKafka::class.java)
    }
}
