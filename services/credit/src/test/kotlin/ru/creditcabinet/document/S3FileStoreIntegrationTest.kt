package ru.creditcabinet.document

import org.junit.jupiter.api.io.TempDir
import org.springframework.http.HttpStatus
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.infrastructure.LocalFileStore
import ru.creditcabinet.document.infrastructure.RoutingFileStore
import ru.creditcabinet.document.infrastructure.S3FileStore
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.S3Exception
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Real pinned SeaweedFS with the same private-bucket configuration as compose; no fake or skip. */
class S3FileStoreIntegrationTest {
    @TempDir lateinit var directory: Path
    private val endpoint = System.getenv("TEST_S3_ENDPOINT") ?: "http://127.0.0.1:18333"
    private val bucket = "cabinet-documents"
    private val accessKey = "dev-cabinet-storage"
    private val secretKey = "dev-storage-secret-change-me"

    private fun store(url: String = endpoint, secret: String = secretKey) = S3FileStore(url, "us-east-1", bucket, accessKey, secret)

    private fun client(): S3Client = S3Client.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.US_EAST_1)
        .forcePathStyle(true)
        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
        .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(5)))
        .overrideConfiguration { it.apiCallTimeout(Duration.ofSeconds(10)) }
        .build()

    @Test
    fun `real S3 preserves bytes and recorded locations across write-mode and client changes`() {
        val name = "${UUID.randomUUID()}.pdf"
        val bytes = ByteArray(600_123) { (it % 251).toByte() }
        val legacy = "Existing local PDF".toByteArray()
        val local = LocalFileStore(directory.toString())
        local.put(name, legacy.inputStream(), legacy.size.toLong())
        try {
            store().use { remote ->
                val router = RoutingFileStore(local, remote, "s3")
                router.put(name, bytes.inputStream(), bytes.size.toLong())
                router.get(name, StorageBackend.LOCAL)!!.content.use { assertContentEquals(legacy, it.readAllBytes()) }
            }
            store().use { reopened ->
                // Switching new uploads back to LOCAL must not hide already uploaded S3 documents.
                val router = RoutingFileStore(local, reopened, "local")
                val stored = router.get(name, StorageBackend.S3)!!
                assertEquals(bytes.size.toLong(), stored.sizeBytes)
                stored.content.use {
                    assertContentEquals(MessageDigest.getInstance("SHA-256").digest(bytes), MessageDigest.getInstance("SHA-256").digest(it.readAllBytes()))
                }
                assertNull(reopened.get("${UUID.randomUUID()}.pdf"))
            }
        } finally {
            client().use { it.deleteObject { request -> request.bucket(bucket).key("documents/$name") } }
        }
    }

    @Test
    fun `repeated small PDF writes preserve every payload on a reused S3 client`() {
        val names = mutableListOf<String>()
        try {
            store().use { remote ->
                repeat(20) { index ->
                    val name = "${UUID.randomUUID()}.pdf"
                    names.add(name)
                    val bytes = "%PDF-1.7\nSynthetic storage fault check $index\n%%EOF\n".toByteArray()
                    remote.put(name, bytes.inputStream(), bytes.size.toLong())
                    remote.get(name)!!.content.use { assertContentEquals(bytes, it.readAllBytes()) }
                }
            }
        } finally {
            client().use { s3 -> names.forEach { name -> s3.deleteObject { it.bucket(bucket).key("documents/$name") } } }
        }
    }

    @Test
    fun `anonymous wrong credentials and runtime administration cannot expose storage`() {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build().use { http ->
            val response = http.send(
                HttpRequest.newBuilder(URI.create("$endpoint/$bucket/")).timeout(Duration.ofSeconds(5)).GET().build(),
                HttpResponse.BodyHandlers.discarding(),
            )
            assertEquals(403, response.statusCode())
        }
        store(secret = "incorrect-test-secret").use {
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertFailsWith<ApiException> { it.get("missing.pdf") }.httpStatus)
        }
        client().use { s3 ->
            assertEquals(403, assertFailsWith<S3Exception> { s3.createBucket { it.bucket("forbidden-${UUID.randomUUID()}") } }.statusCode())
            assertEquals(403, assertFailsWith<S3Exception> { s3.getObject { it.bucket("unrelated-private-bucket").key("documents/private.pdf") } }.statusCode())
            assertEquals(
                403,
                assertFailsWith<S3Exception> {
                    s3.putObject({ it.bucket(bucket).key("outside-documents/${UUID.randomUUID()}") }, RequestBody.fromString("denied"))
                }.statusCode(),
            )
        }
    }

    @Test
    fun `unavailable S3 is a bounded service error and never silently writes locally`() {
        store(url = "http://127.0.0.1:1").use { remote ->
            val router = RoutingFileStore(LocalFileStore(directory.toString()), remote, "s3")
            val started = System.nanoTime()
            assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                assertFailsWith<ApiException> { router.put("unavailable.pdf", byteArrayOf(1).inputStream(), 1) }.httpStatus,
            )
            assertTrue(Duration.ofNanos(System.nanoTime() - started) < Duration.ofSeconds(18))
            assertNull(router.get("unavailable.pdf", StorageBackend.LOCAL))
        }
    }
}
