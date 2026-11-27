package ru.creditcabinet.analytics.infrastructure

import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.DescribeTopicsOptions
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsOptions
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsSpec
import org.apache.kafka.clients.admin.ListOffsetsOptions
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.common.KafkaFuture
import org.apache.kafka.common.TopicPartition
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.dao.DataAccessException
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

enum class Readiness(val code: String) {
    READY("ready"),
    CONSUMER_DISABLED("consumer_disabled"),
    SNAPSHOT_PENDING("snapshot_pending"),
    BROKER_UNAVAILABLE("broker_unavailable"),
    OFFSET_UNAVAILABLE("offset_unavailable"),
    LAG("lag"),
    DLQ("dlq"),
    PROJECTION_UNAVAILABLE("projection_unavailable"),
}

fun interface StatusProjectionReadiness {
    fun check(): Readiness
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = ["ANALYTICS_STATUS_READ_ENDPOINT_ENABLED"], havingValue = "true")
class StatusReadAdminConfiguration {
    @Bean(destroyMethod = "close")
    fun statusReadAdmin(kafkaAdmin: KafkaAdmin): Admin = Admin.create(kafkaAdmin.configurationProperties)
}

@Component
@ConditionalOnProperty(name = ["ANALYTICS_STATUS_READ_ENDPOINT_ENABLED"], havingValue = "true")
class KafkaStatusProjectionReadiness(
    private val generation: StatusProjectionGeneration,
    @Qualifier("statusReadAdmin") private val admin: Admin,
    @Value("\${cabinet.analytics.kafka.enabled:false}") private val kafkaEnabled: Boolean,
    @Value("\${cabinet.analytics.kafka.topic:}") private val topic: String,
    @Value("\${cabinet.analytics.kafka.group-id:}") private val groupId: String,
    @Value("\${cabinet.analytics.kafka.dlq-topic:}") private val dlqTopic: String,
    @Value("\${ANALYTICS_STATUS_READ_MAX_LAG_RECORDS:0}") private val maxLag: Long,
    @Value("\${ANALYTICS_STATUS_READ_KAFKA_TIMEOUT_MS:250}") private val timeoutMs: Int,
) : StatusProjectionReadiness {
    init {
        require(topic.isNotBlank() && groupId.isNotBlank() && dlqTopic.isNotBlank() && topic != dlqTopic)
        require(maxLag >= 0 && timeoutMs > 0)
    }

    override fun check(): Readiness {
        if (!kafkaEnabled) return Readiness.CONSUMER_DISABLED
        val initialized = try {
            generation.isInitialized(topic)
        } catch (_: DataAccessException) {
            return Readiness.PROJECTION_UNAVAILABLE
        }
        if (!initialized) return Readiness.SNAPSHOT_PENDING
        return try {
            readBrokerProgress()
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            Readiness.BROKER_UNAVAILABLE
        } catch (_: Exception) {
            Readiness.BROKER_UNAVAILABLE
        }
    }

    private fun readBrokerProgress(): Readiness {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs.toLong())
        val topics = admin.describeTopics(listOf(topic, dlqTopic), DescribeTopicsOptions().timeoutMs(timeoutMs)).allTopicNames().before(deadline)
        val source = topics.getValue(topic).partitions().map { TopicPartition(topic, it.partition()) }
        val dlq = topics.getValue(dlqTopic).partitions().map { TopicPartition(dlqTopic, it.partition()) }
        if (source.isEmpty() || dlq.isEmpty()) return Readiness.OFFSET_UNAVAILABLE

        val earliest = admin.listOffsets(source.associateWith { OffsetSpec.earliest() }, ListOffsetsOptions().timeoutMs(timeoutMs)).all()
        val latest = admin.listOffsets((source + dlq).associateWith { OffsetSpec.latest() }, ListOffsetsOptions().timeoutMs(timeoutMs)).all()
        val committed = admin.listConsumerGroupOffsets(
            mapOf(groupId to ListConsumerGroupOffsetsSpec().topicPartitions(source)),
            ListConsumerGroupOffsetsOptions().timeoutMs(timeoutMs),
        ).partitionsToOffsetAndMetadata(groupId)

        val begin = earliest.before(deadline).mapValues { (_, result) -> result.offset() }
        val end = latest.before(deadline).mapValues { (_, result) -> result.offset() }
        val consumed = committed.before(deadline).mapNotNull { (partition, offset) ->
            offset?.let { partition to it.offset() }
        }.toMap()
        return evaluateProgress(source, dlq, begin, end, consumed, maxLag)
    }

    private fun <T> KafkaFuture<T>.before(deadline: Long): T =
        get((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
}

internal fun evaluateProgress(
    source: List<TopicPartition>,
    dlq: List<TopicPartition>,
    begin: Map<TopicPartition, Long>,
    end: Map<TopicPartition, Long>,
    consumed: Map<TopicPartition, Long>,
    maxLag: Long,
): Readiness {
    for (partition in dlq) {
        val offset = end[partition] ?: return Readiness.OFFSET_UNAVAILABLE
        if (offset < 0) return Readiness.OFFSET_UNAVAILABLE
        if (offset > 0) return Readiness.DLQ
    }
    var remaining = maxLag
    for (partition in source) {
        val first = begin[partition] ?: return Readiness.OFFSET_UNAVAILABLE
        val last = end[partition] ?: return Readiness.OFFSET_UNAVAILABLE
        val offset = consumed[partition] ?: if (first == 0L && last == 0L) 0L else return Readiness.OFFSET_UNAVAILABLE
        if (first < 0 || offset < first || offset > last) return Readiness.OFFSET_UNAVAILABLE
        val lag = last - offset
        if (lag > remaining) return Readiness.LAG
        remaining -= lag
    }
    return Readiness.READY
}
