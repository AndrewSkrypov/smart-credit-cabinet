package ru.creditcabinet.assessment.messaging

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("cabinet.assessment.kafka")
class AssessmentKafkaSettings(
    val enabled: Boolean = false,
    val bootstrapServers: String = "",
    val environment: String = "",
    val generation: String = "",
    val resultGrace: Duration = Duration.ofSeconds(30),
) {
    init {
        if (enabled) {
            require(environment.matches(NAME) && generation.matches(NAME)) { "Assessment Kafka environment and generation are required" }
            require(bootstrapServers.split(',').all { address ->
                val host = address.trim()
                host.matches(BROKER) && host.substringAfterLast(':').toInt() in 1..65535
            }) { "Assessment Kafka requires explicit broker addresses" }
            require(resultGrace in Duration.ofSeconds(5)..Duration.ofMinutes(5)) { "Invalid assessment result grace" }
        }
    }

    val stream: String get() = "$environment.$generation"
    private val topicPrefix: String get() = "alpha.$environment.assessment.v1.$generation"
    val commandsTopic: String get() = "$topicPrefix.commands"
    val resultsTopic: String get() = "$topicPrefix.results"

    private companion object {
        val NAME = Regex("[a-z0-9][a-z0-9-]{0,31}")
        val BROKER = Regex("[a-zA-Z0-9.-]+:[0-9]{1,5}")
    }
}
