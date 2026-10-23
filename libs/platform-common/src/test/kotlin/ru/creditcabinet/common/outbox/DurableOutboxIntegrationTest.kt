package ru.creditcabinet.common.outbox

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Exercises the actual sender migration and SQL protocol on PostgreSQL, not an in-memory substitute. */
class DurableOutboxIntegrationTest {
    private val schema = "outbox_" + UUID.randomUUID().toString().replace("-", "")
    private val baseUrl = "jdbc:postgresql://${env("DB_HOST", "localhost")}:${env("DB_PORT", "5432")}/${env("COMMON_DB_NAME", "common_test")}"
    private val admin = JdbcTemplate(source(baseUrl))
    private lateinit var jdbc: JdbcTemplate
    private lateinit var transaction: TransactionTemplate
    private lateinit var outbox: DurableOutbox
    private val clock = MutableClock(Instant.EPOCH)

    @BeforeTest
    fun prepare() {
        admin.execute("create schema $schema")
        val source = source("$baseUrl?currentSchema=$schema")
        jdbc = JdbcTemplate(source)
        transaction = TransactionTemplate(DataSourceTransactionManager(source))
        outbox = DurableOutbox(NamedParameterJdbcTemplate(source), transaction, jacksonObjectMapper())
        val root = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
            .first { Files.exists(it.resolve("services/identity/src/main/resources")) }
        jdbc.execute(Files.readString(root.resolve("services/identity/src/main/resources/db/migration/V4__delivery_outbox.sql")))
        jdbc.execute("create table effects (event_key text primary key)")
    }

    @AfterTest
    fun clean() {
        admin.execute("drop schema $schema cascade")
    }

    @Test
    fun `the business row and notification roll back together and enqueue requires a transaction`() {
        assertFailsWith<IllegalStateException> { outbox.enqueue("test", "unsafe", "{}") }
        assertFailsWith<IllegalStateException> {
            transaction.execute {
                jdbc.update("insert into effects values ('business')")
                outbox.enqueue("test", "event", "{}")
                error("Rollback")
            }
        }
        assertEquals(0, count("effects"))
        assertEquals(0, count("delivery_outbox"))
    }

    @Test
    fun `repeating a key is harmless but cannot replace its payload`() {
        val first = enqueue("same")
        assertEquals(first, enqueue("same"))
        assertFailsWith<IllegalArgumentException> { transaction.execute { outbox.enqueue("test", "same", "{\"changed\":true}") } }
        assertEquals(1, count("delivery_outbox"))
    }

    @Test
    fun `an unavailable receiver is retried after process restart and a lost ACK has one effect`() {
        enqueue("event")
        val down = handler { error("Receiver unavailable") }
        assertEquals(1, OutboxWorker(outbox, listOf(down), OutboxSettings(), clock).runDue())
        assertEquals("PENDING", state())
        assertEquals(0, count("effects"))
        clock.at = clock.at.plusSeconds(3)
        val losesAck = handler {
            jdbc.update("insert into effects values ('event') on conflict do nothing")
            error("Response lost after commit")
        }
        OutboxWorker(outbox, listOf(losesAck), OutboxSettings(), clock).runDue()
        assertEquals(1, count("effects"))
        clock.at = clock.at.plusSeconds(5)
        val recovered = handler { jdbc.update("insert into effects values ('event') on conflict do nothing") }
        assertEquals(1, OutboxWorker(outbox, listOf(recovered), OutboxSettings(), clock).runDue())
        assertEquals("DELIVERED", state())
        assertEquals(1, count("effects"))
    }

    @Test
    fun `an expired worker cannot acknowledge or reschedule the new lease`() {
        enqueue("lease")
        val first = assertNotNull(outbox.claim(clock.at, Duration.ofSeconds(30), 3))
        assertNull(outbox.claim(clock.at, Duration.ofSeconds(30), 3))
        clock.at = clock.at.plusSeconds(31)
        val replacement = assertNotNull(outbox.claim(clock.at, Duration.ofSeconds(30), 3))
        assertEquals(first.id, replacement.id)
        assertFalse(outbox.complete(first, clock.at))
        assertFalse(outbox.retry(first, clock.at, clock.at, 3, "OldWorker"))
        assertTrue(outbox.complete(replacement, clock.at))
        assertEquals("DELIVERED", state())
    }

    @Test
    fun `max attempts also bounds a worker that keeps dying before acknowledgement`() {
        enqueue("dies")
        assertNotNull(outbox.claim(clock.at, Duration.ofSeconds(30), 1))
        clock.at = clock.at.plusSeconds(31)
        assertNull(outbox.claim(clock.at, Duration.ofSeconds(30), 1))
        assertEquals("FAILED", state())
    }

    @Test
    fun `failure at the limit is durable and errors cannot expose payload secrets`() {
        enqueue("failure")
        OutboxWorker(outbox, listOf(handler { error("Secret link must never be persisted") }), OutboxSettings(maxAttempts = 1), clock).runDue()
        assertEquals("FAILED", state())
        assertEquals("IllegalStateException", jdbc.queryForObject("select last_error_code from delivery_outbox", String::class.java))
        assertNull(outbox.claim(clock.at.plusSeconds(100), Duration.ofSeconds(30), 1))
    }

    @Test
    fun `two senders claim different rows with skip locked`() {
        repeat(20) { enqueue("parallel-$it") }
        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = (1..2).map {
                executor.submit(
                    Callable {
                        gate.await()
                        generateSequence { outbox.claim(clock.at, Duration.ofSeconds(30), 3) }.map { it.id }.toList()
                    },
                )
            }
            gate.countDown()
            val ids = futures.flatMap { it.get(20, TimeUnit.SECONDS) }
            assertEquals(20, ids.size)
            assertEquals(20, ids.toSet().size)
        } finally {
            executor.shutdownNow()
        }
    }

    private fun enqueue(key: String): UUID {
        val id = transaction.execute { outbox.enqueue("test", key, "{}") }!!
        // The DB stamps enqueue time. A clock frozen before fixture setup can fall behind
        // later inserts on a busy host, accidentally testing not-yet-due rows instead of claims.
        val due = jdbc.queryForObject("select next_attempt_at from delivery_outbox where id=?", Timestamp::class.java, id)!!.toInstant()
        clock.at = maxOf(clock.at, due)
        return id
    }

    @Test
    fun `metrics distinguish missing snapshots, queued and failed deliveries with bounded labels`() {
        val registry = SimpleMeterRegistry()
        val metrics = OutboxMetrics(registry, jdbc.dataSource!!)
        assertTrue(registry.get("cabinet.delivery.outbox.messages").tags("channel", "unknown", "state", "PENDING").gauge().value().isNaN())
        enqueue("measured")
        metrics.refresh()
        assertEquals(1.0, registry.get("cabinet.delivery.outbox.messages").tags("channel", "unknown", "state", "PENDING").gauge().value())
        OutboxWorker(outbox, listOf(handler { error("Unavailable") }), OutboxSettings(maxAttempts = 1), clock, metrics).runDue()
        metrics.refresh()
        assertEquals(1.0, registry.get("cabinet.delivery.outbox.messages").tags("channel", "unknown", "state", "FAILED").gauge().value())
        assertEquals(0.0, registry.get("cabinet.delivery.outbox.oldest").tag("channel", "unknown").gauge().value())
        assertEquals(1L, registry.get("cabinet.delivery.attempt").tags("channel", "unknown", "outcome", "FAILED").timer().count())
    }

    private fun count(table: String): Int = jdbc.queryForObject("select count(*) from $table", Int::class.java)!!

    private fun state(): String = jdbc.queryForObject("select state from delivery_outbox", String::class.java)!!

    private fun handler(action: () -> Unit) = object : DeliveryHandler {
        override val channel = "test"
        override fun deliver(payload: String) = action()
    }

    private fun source(url: String) = DriverManagerDataSource(url, env("DB_USER", "cabinet"), env("DB_PASSWORD", "cabinet"))

    private class MutableClock(var at: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = at
    }

    private companion object {
        fun env(name: String, fallback: String): String = System.getenv(name) ?: fallback
    }
}
