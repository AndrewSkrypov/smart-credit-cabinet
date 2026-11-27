package ru.creditcabinet.assessmentservice.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.client.RestClient
import ru.creditcabinet.assessment.contract.v1.AssessmentRequestedV1
import ru.creditcabinet.assessment.domain.AssessmentPaymentCalculator
import ru.creditcabinet.assessment.domain.AssessmentPayments
import ru.creditcabinet.assessment.domain.SnapshotEvaluation
import ru.creditcabinet.assessment.messaging.AssessmentJson
import ru.creditcabinet.assessment.messaging.AssessmentKafka
import ru.creditcabinet.assessment.messaging.AssessmentKafkaSchedulers
import ru.creditcabinet.assessment.messaging.AssessmentKafkaSettings
import ru.creditcabinet.assessment.messaging.AssessmentOutbox
import ru.creditcabinet.assessment.messaging.validateAssessmentEnvelope
import ru.creditcabinet.assessmentservice.application.AssessmentExecution
import ru.creditcabinet.product.domain.LoanMath
import java.net.URI
import java.net.http.HttpClient
import java.time.Clock
import java.time.Duration

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AssessmentKafkaSettings::class)
class AssessmentProperties

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Import(AssessmentKafkaSchedulers::class)
@ConditionalOnProperty(name = ["cabinet.assessment.kafka.enabled"], havingValue = "true")
class AssessmentRuntime {
    @Bean
    fun assessmentServiceClock(): Clock = Clock.systemUTC()

    @Bean
    fun assessmentExecutions(jdbc: NamedParameterJdbcTemplate, tx: TransactionTemplate, mapper: ObjectMapper, clock: Clock, settings: AssessmentKafkaSettings) =
        AssessmentExecutions(jdbc, tx, AssessmentJson(mapper), AssessmentOutbox(jdbc, tx), clock, settings.stream)

    @Bean
    fun assessmentWire(
        jdbc: NamedParameterJdbcTemplate,
        tx: TransactionTemplate,
        settings: AssessmentKafkaSettings,
        store: AssessmentExecutions,
        mapper: ObjectMapper,
        clock: Clock,
    ): AssessmentKafka {
        val json = AssessmentJson(mapper)
        return AssessmentKafka(settings, AssessmentOutbox(jdbc, tx), settings.resultsTopic, settings.commandsTopic, "executor", clock) { key, payload ->
            val request = validateAssessmentEnvelope {
                json.read(payload, AssessmentRequestedV1::class.java).also {
                    require(it.cycle.generation == settings.stream && it.cycle.applicationId.toString() == key)
                }
            }
            store.accept(request, payload)
        }
    }

    @Bean
    fun assessmentExecutor(
        @Value("\${cabinet.assessment.sources.url:}") url: String,
        @Value("\${cabinet.assessment.sources.api-key:}") apiKey: String,
        builder: RestClient.Builder,
        store: AssessmentExecutions,
        clock: Clock,
    ): AssessmentExecutionWorker {
        val address = URI.create(url)
        require(address.scheme in setOf("http", "https") && address.host != null && address.userInfo == null && apiKey.isNotBlank()) {
            "Assessment requires an explicit source endpoint and API key"
        }
        val transport = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).build()
        val factory = JdkClientHttpRequestFactory(transport).apply { setReadTimeout(Duration.ofSeconds(5)) }
        val client = builder.clone().baseUrl(url).defaultHeader("X-Api-Key", apiKey).requestFactory(factory).build()
        val payments = AssessmentPaymentCalculator { amount, rate, termMonths, revolving ->
            val calculated = LoanMath.calculate(amount, rate, termMonths, revolving)
            AssessmentPayments(calculated.monthlyPayment, calculated.totalPayment)
        }
        return AssessmentExecutionWorker(store, AssessmentExecution(SimulatorAssessmentSources(client, clock), SnapshotEvaluation(payments), clock))
    }

    @Bean("assessmentComputeScheduler")
    fun assessmentComputeScheduler() = ThreadPoolTaskScheduler().apply {
        poolSize = 1
        setThreadNamePrefix("assessment-compute-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(35)
    }
}

class AssessmentExecutionWorker(private val store: AssessmentExecutions, private val execute: AssessmentExecution) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelayString = "PT0.25S", scheduler = "assessmentComputeScheduler")
    fun runDue() {
        val claim = store.claim() ?: return
        val progress = try {
            execute.process(claim.request, claim.collection)
        } catch (exception: Exception) {
            log.warn("Assessment calculation failed ({})", exception.javaClass.simpleName)
            null
        }
        store.finish(claim, progress)
    }
}
