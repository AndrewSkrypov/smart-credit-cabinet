package ru.creditcabinet.documents

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.TlsChannelCredentials
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import ru.creditcabinet.contracts.documents.v1.DocumentsServiceGrpc
import ru.creditcabinet.contracts.documents.v1.Scope
import ru.creditcabinet.document.generated.ArtifactInput
import ru.creditcabinet.document.generated.ArtifactPhase
import ru.creditcabinet.documentsgrpc.DocumentsGrpcClient
import ru.creditcabinet.documentsgrpc.documentsTlsChannel
import ru.creditcabinet.product.domain.LoanMath
import java.io.File
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Required gate: missing infrastructure is a failure, never a skipped test. */
class DocumentsRuntimeTest {
    private val mapper = jacksonObjectMapper().findAndRegisterModules()
    private val certs = File(env("DOCUMENTS_TEST_CERTS"))
    private val port = env("DOCUMENTS_TEST_GRPC_PORT").toInt()
    private val scope = DocumentScope(UUID.fromString(env("DOCUMENTS_TEST_COMPANY")), UUID.fromString(env("DOCUMENTS_TEST_APPLICATION")))
    private fun client(peer: String = "credit", epoch: Long = 2, action: (DocumentsGrpcClient) -> Unit) {
        val channel = documentsTlsChannel("127.0.0.1", port, certs.resolve("$peer.crt"), certs.resolve("$peer.key"), certs.resolve("ca.crt"))
        try { action(DocumentsGrpcClient(channel, mapper, epoch, 5000)) } finally { channel.shutdownNow() }
    }
    @Test
    fun `mTLS scope epoch immutable bytes and scanner rejection are enforced by separate runtime`() {
        val bytes = "%PDF-1.4\nDocuments gate\n%%EOF".toByteArray()
        val id = UUID.randomUUID()
        val record = DocumentRecord(id, scope, "OTHER", "proof.pdf", "$id.pdf", bytes.size.toLong(), UUID.randomUUID(), Instant.now(), "BUSINESS", env("DOCUMENTS_TEST_STORAGE_BACKEND"), sha256(bytes), false)
        client { remote ->
            remote.put(record, bytes)
            remote.put(record, bytes)
            assertEquals(1, remote.list(scope).count { it.id == id })
            assertContentEquals(bytes, remote.read(scope, id, "BUSINESS"))
            assertFailsWith<StatusRuntimeException> { remote.read(scope.copy(companyId = UUID.randomUUID()), id, "BUSINESS") }
            assertFailsWith<StatusRuntimeException> { remote.read(scope.copy(applicationId = UUID.randomUUID()), id, "BUSINESS") }
            assertFailsWith<StatusRuntimeException> { remote.read(scope, id, "CHAT") }
            assertFailsWith<StatusRuntimeException> { remote.put(record.copy(fileName = "changed.pdf"), bytes) }
            val infected = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE".toByteArray()
            assertFailsWith<StatusRuntimeException> { remote.put(record.copy(id = UUID.randomUUID(), storedName = "infected.pdf", sizeBytes = infected.size.toLong(), checksum = sha256(infected)), infected) }
            assertTrue(remote.list(scope).none { it.storedName == "infected.pdf" })
        }
        client("wrong") { remote -> assertEquals(Status.Code.PERMISSION_DENIED, assertFailsWith<StatusRuntimeException> { remote.list(scope) }.status.code) }
        client(epoch = 1) { remote -> assertFailsWith<StatusRuntimeException> { remote.list(scope) } }
        val noCertificate = NettyChannelBuilder.forAddress("127.0.0.1", port, TlsChannelCredentials.newBuilder().trustManager(certs.resolve("ca.crt")).build()).build()
        try {
            assertFailsWith<StatusRuntimeException> { DocumentsServiceGrpc.newBlockingStub(noCertificate).withDeadlineAfter(3, TimeUnit.SECONDS).listDocuments(Scope.getDefaultInstance()) }
        } finally { noCertificate.shutdownNow() }
    }

    @Test
    fun `frozen generation survives concurrent retries and publishes exactly one readiness row`() {
        client { remote ->
            val offer = UUID.randomUUID()
            val text = "Сохранённые условия: 123456 рублей, 6 месяцев, 12 процентов."
            val input = ArtifactInput(scope.applicationId, 777, offer, "Синтетический заёмщик", "000100000004", "Сохранённый продукт", 123456, 6,
                LoanMath.calculate(123456, BigDecimal("12.00"), 6, false), ArtifactPhase.FINAL, text, sha256(text.toByteArray()), false,
                Instant.parse("2026-11-27T00:00:00Z"), "proof-contract", Instant.parse("2026-11-26T00:00:00Z"))
            val frozen = mapper.writeValueAsString(input)
            val id = UUID.randomUUID()
            val record = ArtifactRecord(id, scope, offer, "CONTRACT_PDF", "FINAL", 2, sha256(frozen.toByteArray()), frozen,
                "Сохранённые условия", "contract.pdf", "application/pdf", "generated-$id.pdf", env("DOCUMENTS_TEST_STORAGE_BACKEND"), Instant.now())
            val first = remote.prepare(record)
            assertEquals(first.id, remote.prepare(record.copy(id = UUID.randomUUID(), storedName = "generated-${UUID.randomUUID()}.pdf")).id)
            assertFailsWith<StatusRuntimeException> { remote.prepare(record.copy(id = UUID.randomUUID(), inputJson = frozen.replace("123456", "999999"))) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
            var ready = first
            while (ready.status != "READY" && System.nanoTime() < deadline) {
                Thread.sleep(200)
                ready = remote.artifacts(scope).single { it.id == first.id }
            }
            assertEquals("READY", ready.status)
            val bytes = remote.artifactBytes(scope, ready.id)
            assertEquals(ready.checksum, sha256(bytes))
            assertEquals(ready, remote.retry(scope, ready.id))
            assertContentEquals(bytes, remote.artifactBytes(scope, ready.id))
            assertEquals(frozen, ready.inputJson)
            db("documents") { connection -> connection.prepareStatement("select count(*) from artifact_outbox where artifact_id=?").use { statement ->
                statement.setObject(1, ready.id); statement.executeQuery().use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) }
            } }
        }
    }

    @Test
    fun `Credit HTTP rejects hidden packet private attachment wrong company role and revoked agent`() {
        val actor = UUID.randomUUID()
        val hidden = UUID.fromString(env("DOCUMENTS_TEST_HIDDEN"))
        val visible = UUID.fromString(env("DOCUMENTS_TEST_VISIBLE"))
        val privateId = UUID.fromString(env("DOCUMENTS_TEST_PRIVATE"))
        val base = "/api/v1/applications/${scope.applicationId}"
        val client = token(actor, "CLIENT", scope.companyId)
        assertEquals(200, http("$base/documents/$visible", client).statusCode())
        for (id in listOf(hidden, privateId)) {
            if (id == hidden) assertEquals(404, http("$base/documents/$id", client).statusCode())
            val body = """{"idempotencyKey":"${UUID.randomUUID()}","text":"Проверка доступа","documentIds":["$id"]}"""
            assertEquals(404, http("$base/messages", client, body).statusCode())
            db("credit") { connection -> connection.prepareStatement("select count(*) from message_attachments where document_id=?").use { statement ->
                statement.setObject(1, id); statement.executeQuery().use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) }
            } }
        }
        assertEquals(200, http("$base/documents/$privateId", client).statusCode())
        assertEquals(404, http("$base/documents/$visible", token(actor, "CLIENT", UUID.randomUUID())).statusCode())
        assertEquals(404, http("$base/documents/$visible", token(actor, "UNKNOWN", scope.companyId)).statusCode())
        assertEquals(404, http("/api/v1/agent/applications/${scope.applicationId}/documents/$visible", token(actor, "AGENT", null)).statusCode())
        assertEquals(404, http("/internal/documents/v1", client).statusCode())
    }

    private fun http(path: String, token: String, body: String? = null): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI.create(env("DOCUMENTS_TEST_CREDIT_URL") + path)).header("Authorization", "Bearer $token")
        if (body != null) builder.header("Idempotency-Key", UUID.randomUUID().toString()).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body))
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }
    private fun token(actor: UUID, role: String, company: UUID?): String {
        fun encoded(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val header = encoded("""{"alg":"HS256"}""".toByteArray())
        val payload = encoded(mapper.writeValueAsBytes(mapOf("sub" to actor.toString(), "role" to role, "cid" to company?.toString(), "name" to "Проверка", "exp" to Instant.now().plusSeconds(300).epochSecond)))
        val unsigned = "$header.$payload"
        val key = env("DOCUMENTS_TEST_JWT_SECRET").toByteArray()
        val signature = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(unsigned.toByteArray())
        return "$unsigned.${encoded(signature)}"
    }
    private fun db(name: String, action: (java.sql.Connection) -> Unit) {
        DriverManager.getConnection(env("DOCUMENTS_TEST_DATABASE_BASE") + "/$name", env("DOCUMENTS_TEST_ADMIN_USER"), env("DOCUMENTS_TEST_ADMIN_PASSWORD")).use(action)
    }
    private fun env(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("Required runtime gate configuration: $name")
}
