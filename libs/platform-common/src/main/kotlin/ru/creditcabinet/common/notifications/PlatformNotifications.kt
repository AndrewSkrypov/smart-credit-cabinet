package ru.creditcabinet.common.notifications

import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import ru.creditcabinet.common.outbox.DeliveryHandler
import ru.creditcabinet.common.outbox.DurableOutbox
import ru.creditcabinet.common.outbox.OutboxMetrics
import ru.creditcabinet.common.outbox.OutboxSettings
import ru.creditcabinet.common.outbox.OutboxWorker
import java.time.Duration
import java.util.UUID
import javax.sql.DataSource

data class PlatformNotification(
    val recipientId: UUID? = null,
    val recipientCompanyId: UUID? = null,
    val recipientRole: String? = null,
    val type: String,
    val title: String,
    val body: String,
    val link: String?,
    val dedupKey: String,
    val email: NotificationEmail? = null,
) {
    init {
        require(listOfNotNull(recipientId, recipientCompanyId, recipientRole).size == 1) { "A notification has exactly one address" }
    }

    companion object {
        const val APPLICATION_STATUS = "APPLICATION_STATUS"
        const val ACTION_REQUIRED = "ACTION_REQUIRED"
        const val NEW_REVIEW = "NEW_REVIEW"
    }
}

enum class NotificationEmailKind { DOCUMENTS_REQUESTED, OFFER_READY, SIGNING_READY, DISBURSED, REFUSED, REVIEW_REQUIRED }

data class NotificationEmail(val kind: NotificationEmailKind, val deadline: String? = null)

/** Persists in the caller's business transaction; network delivery never runs on that path. */
fun interface PlatformNotifications {
    fun send(items: List<PlatformNotification>)
}

class OutboxNotifications(private val outbox: DurableOutbox, private val mapper: ObjectMapper) : PlatformNotifications {
    override fun send(items: List<PlatformNotification>) {
        items.forEach { outbox.enqueue("notification", it.dedupKey, mapper.writeValueAsString(it)) }
    }
}

/** The worker owns retries and leases; an HTTP failure must propagate to it. */
class EngagementNotificationsClient(private val client: RestClient, private val mapper: ObjectMapper) : DeliveryHandler {
    override val channel = "notification"

    override fun deliver(payload: String) {
        val item = mapper.readValue(payload, PlatformNotification::class.java)
        client.post().uri("/internal/v1/notifications").contentType(MediaType.APPLICATION_JSON)
            .body(mapOf("items" to listOf(item))).retrieve().toBodilessEntity()
    }
}

@Configuration
@ConditionalOnProperty(name = ["cabinet.outbox.enabled"], havingValue = "false", matchIfMissing = true)
class DisabledNotificationsConfig {
    @Bean
    fun platformNotifications(): PlatformNotifications = PlatformNotifications { items -> check(items.isEmpty()) { "Notification outbox is not enabled" } }
}

@Configuration
@EnableScheduling
@EnableConfigurationProperties(OutboxSettings::class)
@ConditionalOnProperty(name = ["cabinet.outbox.enabled"], havingValue = "true")
class PlatformNotificationsConfig {
    @Bean
    fun durableOutbox(dataSource: DataSource, transactionManager: PlatformTransactionManager, mapper: ObjectMapper): DurableOutbox =
        DurableOutbox(NamedParameterJdbcTemplate(dataSource), TransactionTemplate(transactionManager), mapper)

    @Bean
    fun platformNotifications(outbox: DurableOutbox, mapper: ObjectMapper): PlatformNotifications = OutboxNotifications(outbox, mapper)

    @Bean
    fun notificationDelivery(
        @Value("\${cabinet.engagement.url:}") engagementUrl: String,
        builder: RestClient.Builder,
        mapper: ObjectMapper,
    ): DeliveryHandler {
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(2))
            setReadTimeout(Duration.ofSeconds(5))
        }
        return EngagementNotificationsClient(builder.clone().baseUrl(engagementUrl).requestFactory(requestFactory).build(), mapper)
    }

    @Bean
    fun outboxMetrics(registry: MeterRegistry, dataSource: DataSource): OutboxMetrics = OutboxMetrics(registry, dataSource)

    @Bean
    fun outboxWorker(outbox: DurableOutbox, handlers: List<DeliveryHandler>, settings: OutboxSettings, metrics: OutboxMetrics): OutboxWorker =
        OutboxWorker(outbox, handlers, settings, observation = metrics)
}
