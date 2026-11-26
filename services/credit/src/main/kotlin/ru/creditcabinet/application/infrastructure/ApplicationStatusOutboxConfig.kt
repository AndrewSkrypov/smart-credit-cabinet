package ru.creditcabinet.application.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.application.domain.ApplicationStatusEvents
import ru.creditcabinet.common.outbox.DeliveryOutboxTable
import ru.creditcabinet.common.outbox.DurableOutbox
import ru.creditcabinet.common.outbox.OutboxSettings
import ru.creditcabinet.common.outbox.OutboxTracing
import ru.creditcabinet.common.outbox.OutboxWorker
import java.time.Clock
import javax.sql.DataSource

private const val STATUS_EVENTS_PREFIX = "cabinet.application-status-events"

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = STATUS_EVENTS_PREFIX, name = ["enabled"], havingValue = "false", matchIfMissing = true)
class DisabledApplicationStatusEventsConfig {
    @Bean
    fun applicationStatusEvents(): ApplicationStatusEvents = ApplicationStatusEvents { }
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = STATUS_EVENTS_PREFIX, name = ["enabled"], havingValue = "true")
class ApplicationStatusOutboxConfig {
    @Bean
    fun applicationStatusEvents(
        jdbc: NamedParameterJdbcTemplate,
        transactionManager: PlatformTransactionManager,
        mapper: ObjectMapper,
        tracing: ObjectProvider<OutboxTracing>,
        @Value("\${cabinet.application-status-events.topic:}") topic: String,
    ): JdbcApplicationStatusOutbox =
        JdbcApplicationStatusOutbox(
            DurableOutbox(
                jdbc,
                TransactionTemplate(transactionManager),
                mapper,
                tracing.ifAvailable,
                table = DeliveryOutboxTable.APPLICATION_STATUS,
                streamGeneration = topic,
            ),
            mapper,
        )

    @Bean
    fun applicationStatusOutboxMetrics(
        registry: MeterRegistry,
        dataSource: DataSource,
        @Value("\${cabinet.application-status-events.topic:}") topic: String,
    ): ApplicationStatusOutboxMetrics = ApplicationStatusOutboxMetrics(registry, dataSource, topic)

    @Bean("applicationStatusDeliveryScheduler")
    @ConditionalOnProperty(prefix = STATUS_EVENTS_PREFIX, name = ["worker-enabled"], havingValue = "true", matchIfMissing = true)
    fun applicationStatusDeliveryScheduler(): ThreadPoolTaskScheduler = ThreadPoolTaskScheduler().apply {
        poolSize = 1
        setThreadNamePrefix("application-status-delivery-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(20)
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = STATUS_EVENTS_PREFIX, name = ["worker-enabled"], havingValue = "true", matchIfMissing = true)
    fun applicationStatusDelivery(
        events: JdbcApplicationStatusOutbox,
        mapper: ObjectMapper,
        metrics: ApplicationStatusOutboxMetrics,
        tracing: ObjectProvider<OutboxTracing>,
        clock: Clock,
        @Value("\${cabinet.application-status-events.bootstrap-servers:}") bootstrapServers: String,
        @Value("\${cabinet.application-status-events.topic:}") topic: String,
    ): ApplicationStatusDelivery {
        require(bootstrapServers.isNotBlank() && topic.isNotBlank()) { "Application status delivery requires Kafka bootstrap servers and topic" }

        val publisher = KafkaApplicationStatusPublisher(topic, mapper) {
            KafkaProducer(
                mapOf(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrapServers,
                    ProducerConfig.CLIENT_ID_CONFIG to "credit-application-status",
                    ProducerConfig.ACKS_CONFIG to "all",
                    ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG to true,
                    ProducerConfig.MAX_BLOCK_MS_CONFIG to 5_000,
                    ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG to 5_000,
                    ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG to 10_000,
                ),
                StringSerializer(),
                StringSerializer(),
            )
        }
        val worker = OutboxWorker(events.outbox, listOf(publisher), OutboxSettings(), clock, metrics, tracing.ifAvailable)
        return ApplicationStatusDelivery(publisher, worker)
    }
}

/** A Kafka send occupies only this scheduler and the claim's lease. */
class ApplicationStatusDelivery internal constructor(
    private val publisher: KafkaApplicationStatusPublisher,
    private val worker: OutboxWorker,
) : AutoCloseable {
    @Scheduled(fixedDelayString = "\${cabinet.application-status-events.poll-interval:PT0.1S}", scheduler = "applicationStatusDeliveryScheduler")
    fun poll() {
        worker.runDue(limit = 1)
    }

    override fun close() = publisher.close()
}
