package ru.creditcabinet.assessment.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.assessment.application.AssessmentRouting
import ru.creditcabinet.assessment.contract.v1.AssessmentCompletedV1
import ru.creditcabinet.assessment.messaging.AssessmentJson
import ru.creditcabinet.assessment.messaging.AssessmentKafka
import ru.creditcabinet.assessment.messaging.AssessmentKafkaSchedulers
import ru.creditcabinet.assessment.messaging.AssessmentKafkaSettings
import ru.creditcabinet.assessment.messaging.AssessmentOutbox
import ru.creditcabinet.assessment.messaging.validateAssessmentEnvelope
import java.time.Clock

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AssessmentKafkaSettings::class)
class CreditAssessmentConfiguration {
    @Bean
    fun assessmentRouting(settings: AssessmentKafkaSettings) = AssessmentRouting(settings.enabled, settings.stream, settings.resultGrace)

    @Bean
    fun creditAssessmentStore(jdbc: NamedParameterJdbcTemplate, tx: TransactionTemplate, mapper: ObjectMapper, clock: Clock) =
        JdbcCreditAssessmentStore(jdbc, tx, AssessmentJson(mapper), AssessmentOutbox(jdbc, tx), clock)
}

@Configuration(proxyBeanMethods = false)
@Import(AssessmentKafkaSchedulers::class)
@ConditionalOnProperty(name = ["cabinet.assessment.kafka.enabled"], havingValue = "true")
class CreditAssessmentKafkaConfiguration {
    @Bean
    fun creditAssessmentWire(
        jdbc: NamedParameterJdbcTemplate,
        tx: TransactionTemplate,
        store: JdbcCreditAssessmentStore,
        mapper: ObjectMapper,
        settings: AssessmentKafkaSettings,
        clock: Clock,
    ): AssessmentKafka {
        val json = AssessmentJson(mapper)
        return AssessmentKafka(settings, AssessmentOutbox(jdbc, tx), settings.commandsTopic, settings.resultsTopic, "credit", clock) { key, payload ->
            val result = validateAssessmentEnvelope {
                json.read(payload, AssessmentCompletedV1::class.java).also {
                    require(it.cycle.generation == settings.stream && it.cycle.applicationId.toString() == key)
                }
            }
            store.receive(result, payload)
        }
    }
}
