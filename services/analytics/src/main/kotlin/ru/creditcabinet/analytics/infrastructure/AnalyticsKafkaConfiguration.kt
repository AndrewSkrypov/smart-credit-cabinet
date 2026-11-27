package ru.creditcabinet.analytics.infrastructure

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.autoconfigure.kafka.ConcurrentKafkaListenerContainerFactoryConfigurer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.FixedBackOff

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "cabinet.analytics.kafka", name = ["enabled"], havingValue = "true")
class AnalyticsKafkaConfiguration {
    @Bean
    fun analyticsKafkaListenerContainerFactory(
        configurer: ConcurrentKafkaListenerContainerFactoryConfigurer,
        consumers: ConsumerFactory<Any, Any>,
    ): ConcurrentKafkaListenerContainerFactory<Any, Any> {
        val factory = ConcurrentKafkaListenerContainerFactory<Any, Any>()
        configurer.configure(factory, consumers)
        factory.setBatchListener(false)
        factory.containerProperties.apply {
            ackMode = ContainerProperties.AckMode.MANUAL_IMMEDIATE
            assignmentCommitOption = ContainerProperties.AssignmentCommitOption.NEVER
            setSyncCommits(true)
            setAsyncAcks(false)
            kafkaConsumerProperties.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
        }
        // Only the listener may quarantine a record. Infrastructure failures must leave its offset pending.
        factory.setCommonErrorHandler(
            DefaultErrorHandler(FixedBackOff(1_000, FixedBackOff.UNLIMITED_ATTEMPTS)).apply {
                setClassifications(emptyMap(), true)
                setAckAfterHandle(false)
            },
        )
        return factory
    }
}
