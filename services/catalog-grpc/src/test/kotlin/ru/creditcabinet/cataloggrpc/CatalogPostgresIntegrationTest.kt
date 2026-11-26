package ru.creditcabinet.cataloggrpc

import io.grpc.Server
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.TlsChannelCredentials
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.jsonwebtoken.Jwts
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.mock.env.MockEnvironment
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.ForbiddenException
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.contracts.catalog.v1.CatalogServiceGrpc
import ru.creditcabinet.contracts.catalog.v1.ListCurrentProductsRequest
import ru.creditcabinet.product.domain.ProductLifecycleStatus
import java.io.File
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CatalogPostgresIntegrationTest {
    private val secret = "catalog-integration-test-signing-secret"
    private val verifier = JwtVerifier(secret, MockEnvironment())
    private val actor = UUID.randomUUID()
    private val certificates = File(env("CATALOG_TEST_CERTS"))

    @Test
    fun `the independent TLS runtime enforces caller identity and commits one audited replacement`() {
        SpringApplicationBuilder(CatalogGrpcApplication::class.java).web(WebApplicationType.NONE)
            .properties(
                "cabinet.catalog.grpc.database.url=${env("CATALOG_TEST_JDBC_URL")}",
                "cabinet.catalog.grpc.database.user=${env("CATALOG_TEST_DATABASE_USER")}",
                "cabinet.catalog.grpc.database.password=${System.getenv("CATALOG_TEST_DATABASE_PASSWORD").orEmpty()}",
                "cabinet.catalog.grpc.server.port=0",
                "cabinet.catalog.grpc.server.certificate=${certificates.resolve("server.crt")}",
                "cabinet.catalog.grpc.server.key=${certificates.resolve("server.key")}",
                "cabinet.catalog.grpc.server.client-ca=${certificates.resolve("ca.crt")}",
                "JWT_SECRET=$secret",
            )
            .run().use { context ->
                val port = context.getBean(Server::class.java).port
                val valid = catalogTlsChannel(
                    "127.0.0.1",
                    port,
                    certificates.resolve("credit.crt"),
                    certificates.resolve("credit.key"),
                    certificates.resolve("ca.crt"),
                )
                val wrongPeer = catalogTlsChannel(
                    "127.0.0.1",
                    port,
                    certificates.resolve("wrong.crt"),
                    certificates.resolve("wrong.key"),
                    certificates.resolve("ca.crt"),
                )
                val noCert = NettyChannelBuilder.forAddress(
                    "127.0.0.1",
                    port,
                    TlsChannelCredentials.newBuilder().trustManager(certificates.resolve("ca.crt")).build(),
                ).build()
                try {
                    assertEquals(Status.Code.PERMISSION_DENIED, denied(wrongPeer))
                    assertEquals(Status.Code.UNAVAILABLE, denied(noCert))
                    val client = CatalogGrpcClient(valid, 3000)
                    val base = client.current("WORKING_CAPITAL", effectiveOnly = true).single().product
                    val code = "R1_" + UUID.randomUUID().toString().take(8).uppercase()
                    val draft = base.copy(code = code, lifecycleStatus = ProductLifecycleStatus.DRAFT)
                    val admin = token(Roles.RISK_ADMIN)
                    assertFailsWith<ForbiddenException> { client.create(draft, "Другая роль", token(Roles.MANAGER)) }
                    val created = client.create(draft, "Регистрация тарифа", admin)
                    assertEquals(1L, created.product.version)
                    assertEquals(actor, created.authorId)
                    assertEquals("Проверка Catalog", created.authorName)
                    assertEquals(Roles.RISK_ADMIN, created.authorRole)
                    assertTrue(client.current(code, effectiveOnly = true).isEmpty())

                    val candidate = created.product.copy(lifecycleStatus = ProductLifecycleStatus.ACTIVE)
                    Executors.newFixedThreadPool(2).use { executor ->
                        val outcomes = listOf("Публикация первая", "Публикация вторая").map { reason ->
                            executor.submit<Result<Long>> { runCatching { client.replace(candidate, 1, reason, admin).product.version } }
                        }.map { it.get(5, TimeUnit.SECONDS) }
                        assertEquals(listOf(2L), outcomes.mapNotNull { it.getOrNull() })
                        assertEquals(1, outcomes.count { it.exceptionOrNull() is ConflictException })
                    }
                    val history = client.history(code)
                    assertEquals(listOf(2L, 1L), history.map { it.product.version })
                    assertTrue(history.all { it.authorId == actor })
                    val snapshot = assertNotNull(client.snapshotForSubmission(code))
                    assertEquals(2L, snapshot.selected.version)
                    assertEquals(history.first(), snapshot.revisions.single { it.product.code == code })
                    assertTrue(snapshot.products.any { it.code == base.code })
                } finally {
                    valid.shutdownNow()
                    wrongPeer.shutdownNow()
                    noCert.shutdownNow()
                }
            }
    }

    private fun denied(channel: io.grpc.Channel): Status.Code = assertFailsWith<StatusRuntimeException> {
        CatalogServiceGrpc.newBlockingStub(channel).withDeadlineAfter(3, TimeUnit.SECONDS)
            .listCurrentProducts(ListCurrentProductsRequest.getDefaultInstance())
    }.status.code

    private fun token(role: String): String = "Bearer " + Jwts.builder().subject(actor.toString())
        .claim(TokenClaims.ROLE, role)
        .claim(TokenClaims.FULL_NAME, "Проверка Catalog")
        .expiration(Date(System.currentTimeMillis() + 60_000))
        .signWith(verifier.signingKey)
        .compact()

    private fun env(key: String) = checkNotNull(System.getenv(key)) { "Required environment variable $key" }
}
