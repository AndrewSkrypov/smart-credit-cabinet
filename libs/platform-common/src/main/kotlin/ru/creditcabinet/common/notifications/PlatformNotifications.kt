package ru.creditcabinet.common.notifications

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.client.RestClient
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * One notification for the bell in the header; stored and shown by engagement. Addressed to one
 * person, to everyone of a company, or to everyone with a bank role: exactly one is set, and
 * engagement turns the address into people.
 */
data class PlatformNotification(
    val recipientId: UUID? = null,
    val recipientCompanyId: UUID? = null,
    val recipientRole: String? = null,
    /** One of engagement's notification types, e.g. [APPLICATION_STATUS]. */
    val type: String,
    val title: String,
    val body: String,
    /** A path inside the cabinet, e.g. "/client/applications/<id>". */
    val link: String?,
    /** The same key for the same event: a repeated delivery is stored once. */
    val dedupKey: String,
) {
    init {
        require(listOfNotNull(recipientId, recipientCompanyId, recipientRole).size == 1) { "A notification has exactly one address" }
    }

    companion object {
        /** The application moved to another stage; nothing is required from the client. */
        const val APPLICATION_STATUS = "APPLICATION_STATUS"

        /** The client has to do something: add documents, accept an offer, sign. */
        const val ACTION_REQUIRED = "ACTION_REQUIRED"

        /** Bank: an application waits for a manual decision. */
        const val NEW_REVIEW = "NEW_REVIEW"
    }
}

/**
 * Hands notifications to engagement once the caller's transaction has committed. Never
 * throws and never waits: a notification must not undo or slow down the action it is about.
 */
fun interface PlatformNotifications {
    fun send(items: List<PlatformNotification>)
}

/**
 * Posts to engagement's /internal/v1/notifications on a background thread after the commit.
 * If engagement is down the notification is logged and dropped: the action itself stands.
 * No retries, so no duplicates; the dedup key also covers a request that was retried below.
 */
class EngagementNotificationsClient(private val client: RestClient) :
    PlatformNotifications,
    DisposableBean {
    private val log = LoggerFactory.getLogger(javaClass)
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "notifications-sender").apply { isDaemon = true }
    }

    override fun send(items: List<PlatformNotification>) {
        if (items.isEmpty()) return
        val deliver = { executor.execute { post(items) } }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = deliver()
                },
            )
        } else {
            deliver()
        }
    }

    private fun post(items: List<PlatformNotification>) {
        try {
            client.post().uri("/internal/v1/notifications").contentType(MediaType.APPLICATION_JSON)
                .body(mapOf("items" to items)).retrieve().toBodilessEntity()
        } catch (exception: Exception) {
            log.warn("Notifications not delivered ({}): {}", items.joinToString { it.dedupKey }, exception.message)
        }
    }

    override fun destroy() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}

@Configuration
class PlatformNotificationsConfig {
    @Bean
    fun platformNotifications(
        @Value("\${cabinet.engagement.url:}") engagementUrl: String,
        restClientBuilder: RestClient.Builder,
    ): PlatformNotifications {
        if (engagementUrl.isBlank()) {
            val log = LoggerFactory.getLogger(PlatformNotifications::class.java)
            return PlatformNotifications { items -> log.info("Notifications not sent (cabinet.engagement.url is not set): {}", items.map { it.dedupKey }) }
        }
        val requestFactory = SimpleClientHttpRequestFactory().apply {
            setConnectTimeout(Duration.ofSeconds(2))
            setReadTimeout(Duration.ofSeconds(5))
        }
        return EngagementNotificationsClient(restClientBuilder.clone().baseUrl(engagementUrl).requestFactory(requestFactory).build())
    }
}
