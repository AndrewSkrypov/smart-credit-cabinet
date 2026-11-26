package ru.creditcabinet.assessmentservice

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.assessment.contract.v1.AssessmentCompletedV1
import ru.creditcabinet.assessment.contract.v1.AssessmentOutcomeV1
import ru.creditcabinet.assessment.contract.v1.AssessmentRequestedV1
import ru.creditcabinet.assessment.domain.SourceKind
import ru.creditcabinet.assessment.messaging.AssessmentJson
import ru.creditcabinet.assessment.messaging.AssessmentKafka
import ru.creditcabinet.assessment.messaging.AssessmentKafkaSettings
import ru.creditcabinet.assessment.messaging.AssessmentOutbox
import ru.creditcabinet.assessmentservice.application.ExecutionProgress
import ru.creditcabinet.assessmentservice.infrastructure.AssessmentExecutions
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "R2_KAFKA_BOOTSTRAP", matches = "127\\.0\\.0\\.1:[0-9]+")
class AssessmentKafkaExchangeTest {
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var namedJdbc: NamedParameterJdbcTemplate

    @Autowired private lateinit var tx: TransactionTemplate

    @Autowired private lateinit var mapper: ObjectMapper

    @Test
    fun `native broker retries a rolled back command and publishes one durable result`() {
        val address = checkNotNull(System.getenv("R2_KAFKA_BOOTSTRAP"))
        val settings = AssessmentKafkaSettings(true, address, "synthetic", UUID.randomUUID().toString().take(15))
        val clock = Clock.systemUTC()
        val json = AssessmentJson(mapper)
        val outbox = AssessmentOutbox(namedJdbc, tx)
        val store = AssessmentExecutions(namedJdbc, tx, json, outbox, clock, settings.stream)
        val fixturePath = Path.of("../../contracts/events/credit/assessment/fixtures/assessment-requested.json")
        val fixture = json.read(Files.readString(fixturePath), AssessmentRequestedV1::class.java)
        val id = UUID.randomUUID()
        val start = clock.instant()
        val request = fixture.copy(
            cycle = fixture.cycle.copy(generation = settings.stream, cycleId = id),
            bureauConsent = null,
            bankPrefetch = fixture.bankPrefetch?.copy(receivedAt = start.minusSeconds(1)),
            startsAt = start,
            deadline = start.plusSeconds(120),
        )
        val group = "alpha.${settings.stream}.assessment.v1.pg-proof"
        val key = request.cycle.applicationId.toString()
        val topics = listOf(settings.commandsTopic, settings.resultsTopic)
        Admin.create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to address)).use { admin ->
            admin.createTopics(topics.map { NewTopic(it, 1, 1.toShort()) }).all().get(15, TimeUnit.SECONDS)
            try {
                var deliveries = 0
                var result: AssessmentCompletedV1? = null
                AssessmentKafka(settings, outbox, topics[1], topics[0], "pg-proof", clock) { receivedKey, payload ->
                    assertEquals(key, receivedKey)
                    val received = json.read(payload, AssessmentRequestedV1::class.java)
                    deliveries++
                    if (deliveries == 1) {
                        tx.executeWithoutResult {
                            store.accept(received, payload)
                            throw IllegalArgumentException("Synthetic transaction rollback")
                        }
                    }
                    store.accept(received, payload)
                }.use { executor ->
                    AssessmentKafka(settings, outbox, topics[0], topics[1], "result-proof", clock) { receivedKey, payload ->
                        assertEquals(key, receivedKey)
                        result = json.read(payload, AssessmentCompletedV1::class.java)
                    }.use { recipient ->
                        KafkaProducer(
                            mapOf(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to address),
                            StringSerializer(),
                            StringSerializer(),
                        ).use { producer ->
                            repeat(2) {
                                producer.send(ProducerRecord(topics[0], key, json.write(request))).get(10, TimeUnit.SECONDS)
                            }
                        }
                        pollUntil { executor.consume(); deliveries >= 1 }
                        val committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS)
                        assertTrue(committed.values.all { it.offset() == 0L })
                        assertEquals(0, count("assessment_execution", id))
                        pollUntil { executor.consume(); deliveries >= 3 }
                        assertEquals(1, count("assessment_execution", id))

                        val claim = assertNotNull(store.claim())
                        val collection = claim.collection
                        val completed = AssessmentCompletedV1(
                            request.cycle,
                            collection.receipts,
                            collection.facts,
                            AssessmentOutcomeV1.INCOMPLETE,
                            SourceKind.entries.toSet() - collection.receipts.keys,
                            clock.instant(),
                            null,
                            null,
                            null,
                        )
                        assertTrue(store.finish(claim, ExecutionProgress(collection, completed)))
                        assertEquals(1, count("assessment_exchange_outbox", id))
                        executor.publish()
                        pollUntil { recipient.consume(); result != null }
                        assertNotNull(result).validateAgainst(request)
                    }
                }
            } finally {
                admin.deleteTopics(topics).all().get(15, TimeUnit.SECONDS)
            }
        }
    }

    private fun count(table: String, id: UUID): Int? = jdbc.queryForObject("select count(*) from $table where cycle_id=?", Int::class.java, id)

    private fun pollUntil(check: () -> Boolean) {
        repeat(60) {
            if (check()) return
        }
        error("Kafka test did not reach its expected state")
    }
}
