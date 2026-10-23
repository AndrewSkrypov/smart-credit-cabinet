package ru.creditcabinet.common.security

import io.jsonwebtoken.Jwts
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.valkey.DefaultJedisClientConfig
import io.valkey.HostAndPort
import io.valkey.Jedis
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mock.env.MockEnvironment
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.exception.UnauthorizedException
import ru.creditcabinet.common.limits.LimitWindow
import ru.creditcabinet.common.limits.SharedLimitExceeded
import ru.creditcabinet.common.limits.SharedLimits
import ru.creditcabinet.common.limits.ValkeyProperties
import ru.creditcabinet.common.limits.ValkeyState
import java.net.ServerSocket
import java.time.Duration
import java.time.Instant
import java.util.Date
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Requires actual Valkey at 16379 and PostgreSQL common_test; absence is a failure, not a skip. */
class SharedStateIntegrationTest {
    private val secret = "integration-namespace-${UUID.randomUUID()}"
    private val metrics = SimpleMeterRegistry()
    private val properties = ValkeyProperties(
        enabled = true,
        port = System.getenv("VALKEY_TEST_PORT")?.toInt() ?: 16379,
        password = "dev-valkey-integration-password",
    )

    private fun state() = ValkeyState(properties, secret)

    @Test
    fun `two independent clients accept exactly thirty of sixty simultaneous uploads`() {
        state().use { first ->
            state().use { second ->
                val clients = listOf(SharedLimits(first, metrics), SharedLimits(second, metrics))
                val window = LimitWindow("upload", UUID.randomUUID().toString(), 30, Duration.ofMinutes(10))
                val executor = Executors.newFixedThreadPool(12)
                try {
                    val replies = executor.invokeAll(
                        (0 until 60).map { index ->
                            Callable {
                                try {
                                    clients[index % 2].acquire("limited", window)
                                    200
                                } catch (exception: ApiException) {
                                    exception.httpStatus.value()
                                }
                            }
                        },
                    ).map { it.get(5, TimeUnit.SECONDS) }
                    assertEquals(mapOf(200 to 30, 429 to 30), replies.groupingBy { it }.eachCount())
                } finally {
                    executor.shutdownNow()
                }
                val denied = assertFailsWith<SharedLimitExceeded> { clients[1].acquire("limited", window) }
                assertTrue(denied.retryAfterSeconds in 1..600)
            }
        }
    }

    @Test
    fun `failed multi-key acquisition does not spend the second quota`() {
        state().use { state ->
            val limits = SharedLimits(state, metrics)
            val full = LimitWindow("recovery-email", "full@example.invalid", 1, Duration.ofMinutes(15))
            val address = LimitWindow("recovery-ip", "192.0.2.10", 1, Duration.ofMinutes(15))
            limits.acquire("limited", full)
            repeat(4) { assertFailsWith<SharedLimitExceeded> { limits.acquire("limited", full, address) } }
            limits.acquire("limited", full.copy(subject = "new@example.invalid"), address)
            assertFailsWith<SharedLimitExceeded> { limits.acquire("limited", address) }
        }
    }

    @Test
    fun `window expiry and successful sign-in reset are shared`() {
        state().use { first ->
            state().use { second ->
                val a = SharedLimits(first, metrics)
                val b = SharedLimits(second, metrics)
                val window = LimitWindow("signin", "expiry@example.invalid", 1, Duration.ofMillis(500))
                a.acquire("limited", window)
                assertFailsWith<SharedLimitExceeded> { b.acquire("limited", window) }
                Thread.sleep(600)
                b.acquire("limited", window)
                a.reset(window.scope, window.subject)
                b.acquire("limited", window)
            }
        }
    }

    @Test
    fun `out of order revocation publication cannot lower a watermark`() {
        state().use { state ->
            val revocations = RevocationWatermarks(state, metrics)
            val id = UUID.randomUUID()
            val executor = Executors.newFixedThreadPool(8)
            try {
                executor.invokeAll((1..40).shuffled().map { version -> Callable { revocations.observe(id, version) } }).forEach { it.get() }
            } finally {
                executor.shutdownNow()
            }
            revocations.observe(id, 2)
            assertTrue(revocations.isOlder(id, 39))
            assertFalse(revocations.isOlder(id, 40))
            assertEquals("40", state.read(state.key("credentials", id.toString())))
        }
    }

    @Test
    fun `cached equality never replaces authority and a cached revocation can deny during database outage`() {
        state().use { state ->
            val revocations = RevocationWatermarks(state, metrics)
            val id = UUID.randomUUID()
            var current: Int? = 4
            var reads = 0
            val verifier = JwtVerifier(secret, MockEnvironment(), {
                reads++
                current
            }, revocations)
            revocations.observe(id, 4)
            val token = token(verifier, id, 4)
            verifier.parseClaims(token)
            current = 5 // Deliberately no cache publication: a stale equal entry must still be denied.
            assertFailsWith<UnauthorizedException> { verifier.parseClaims(token) }
            assertEquals(2, reads)
            current = null
            assertFailsWith<UnauthorizedException> { verifier.parseClaims(token) }
            assertEquals(2, reads)
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertFailsWith<ApiException> { verifier.parseClaims(token(verifier, id, 5)) }.httpStatus)
        }
    }

    @Test
    fun `cache loss falls back to fresh authority and combined outage fails closed`() {
        val closedPort = ServerSocket(0).use { it.localPort }
        ValkeyState(ValkeyProperties(enabled = true, port = closedPort, password = properties.password), secret).use { unavailable ->
            val revocations = RevocationWatermarks(unavailable, metrics)
            val id = UUID.randomUUID()
            var current: Int? = 2
            val verifier = JwtVerifier(secret, MockEnvironment(), { current }, revocations)
            assertFailsWith<UnauthorizedException> { verifier.parseClaims(token(verifier, id, 1)) }
            verifier.parseClaims(token(verifier, id, 2))
            current = null
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertFailsWith<ApiException> { verifier.parseClaims(token(verifier, id, 2)) }.httpStatus)
            val start = System.nanoTime()
            val failure = assertFailsWith<ApiException> {
                SharedLimits(unavailable, metrics).acquire("limited", LimitWindow("upload", id.toString(), 30, Duration.ofMinutes(10)))
            }
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.httpStatus)
            assertTrue(Duration.ofNanos(System.nanoTime() - start) < Duration.ofSeconds(2))
        }
    }

    @Test
    fun `a connection closed by the server is replaced before a quota command is sent`() {
        val config = DefaultJedisClientConfig.builder().password(properties.password).build()
        Jedis(HostAndPort(properties.host, properties.port), config).use { admin ->
            state().use { first ->
                state().use { second ->
                    first.read(first.key("probe", "stale"))
                    second.read(second.key("probe", "stale"))
                    admin.sendCommand(io.valkey.Protocol.Command.CLIENT, "KILL", "TYPE", "normal", "SKIPME", "yes")
                    val window = LimitWindow("upload", "stale-connection", 1, Duration.ofMinutes(1))
                    SharedLimits(first, metrics).acquire("limited", window)
                    assertFailsWith<SharedLimitExceeded> { SharedLimits(second, metrics).acquire("limited", window) }
                }
            }
        }
    }

    @Test
    fun `server timeout rejects the operation instead of starting a local counter`() {
        val adminConfig = DefaultJedisClientConfig.builder().password(properties.password).build()
        Jedis(HostAndPort(properties.host, properties.port), adminConfig).use { admin ->
            state().use { state ->
                // Connect before pausing: measure command timeout rather than socket establishment.
                state.read(state.key("probe", "timeout"))
                admin.clientPause(1200)
                val start = System.nanoTime()
                val failure = assertFailsWith<ApiException> {
                    SharedLimits(state, metrics).acquire("limited", LimitWindow("upload", "timeout", 1, Duration.ofMinutes(1)))
                }
                assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.httpStatus)
                assertTrue(Duration.ofNanos(System.nanoTime() - start) < Duration.ofSeconds(2))
                Thread.sleep(1300)
                assertEquals("PONG", admin.ping())
            }
        }
    }

    @Test
    fun `database commit publishes but rollback never publishes a phantom revocation`() {
        val port = System.getenv("DB_PORT") ?: "5432"
        val source = DriverManagerDataSource("jdbc:postgresql://localhost:$port/common_test", "cabinet", "cabinet")
        val jdbc = JdbcTemplate(source)
        val transaction = TransactionTemplate(DataSourceTransactionManager(source))
        val table = "revocation_test_" + UUID.randomUUID().toString().replace("-", "")
        jdbc.execute("create table $table (version integer not null)")
        try {
            jdbc.update("insert into $table values (0)")
            state().use { state ->
                val id = UUID.randomUUID()
                val revocations = RevocationWatermarks(state, metrics)
                transaction.executeWithoutResult {
                    jdbc.update("update $table set version=1")
                    revocations.afterCommit(id, 1)
                    assertFalse(revocations.isOlder(id, 0))
                }
                assertTrue(revocations.isOlder(id, 0))
                transaction.executeWithoutResult { status ->
                    jdbc.update("update $table set version=2")
                    revocations.afterCommit(id, 2)
                    status.setRollbackOnly()
                }
                assertEquals(1, jdbc.queryForObject("select version from $table", Int::class.java))
                assertFalse(revocations.isOlder(id, 1))
            }
        } finally {
            jdbc.execute("drop table $table")
        }
    }

    @Test
    fun `key names hide subjects and signing-secret rotation isolates restored state`() {
        state().use { first ->
            ValkeyState(properties, "rotated-secret-${UUID.randomUUID()}").use { second ->
                val key = first.key("signin", "person@example.invalid")
                assertFalse(key.contains("person"))
                assertTrue(key.matches(Regex("cabinet:v1:signin:[a-f0-9]{64}")))
                assertFalse(key == second.key("signin", "person@example.invalid"))
            }
        }
    }

    private fun token(verifier: JwtVerifier, id: UUID, version: Int): String = Jwts.builder().subject(id.toString())
        .claim(TokenClaims.ROLE, "CLIENT").claim(TokenClaims.CREDENTIALS_VERSION, version)
        .expiration(Date.from(Instant.now().plusSeconds(60))).signWith(verifier.signingKey).compact()
}
