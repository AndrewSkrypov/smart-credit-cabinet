package ru.creditcabinet.document.infrastructure

import jakarta.annotation.PreDestroy
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.domain.StoredFile
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.io.InputStream
import java.net.URI
import java.time.Duration

/** Private S3-compatible objects; authorization and filenames remain in the credit API. */
@Component
class S3FileStore(
    @Value("\${cabinet.storage.s3.endpoint:http://seaweedfs:8333}") endpoint: String,
    @Value("\${cabinet.storage.s3.region:us-east-1}") private val region: String,
    @Value("\${cabinet.storage.s3.bucket:cabinet-documents}") private val bucket: String,
    @Value("\${cabinet.storage.s3.access-key:dev-cabinet-storage}") private val accessKey: String,
    @Value("\${cabinet.storage.s3.secret-key:dev-storage-secret-change-me}") private val secretKey: String,
) : FileStore, AutoCloseable {
    private val endpointUri = URI.create(endpoint)
    override val writeBackend = StorageBackend.S3

    init {
        require(
            endpointUri.scheme in setOf("http", "https") &&
                endpointUri.host != null &&
                endpointUri.userInfo == null &&
                endpointUri.query == null &&
                endpointUri.fragment == null &&
                endpointUri.path in setOf("", "/"),
        ) { "Проверьте внутренний адрес S3-хранилища" }
        require(bucket.matches(Regex("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) && region.isNotBlank() && accessKey.isNotBlank() && secretKey.isNotBlank()) {
            "Проверьте настройки S3-хранилища"
        }
    }

    private val clientHolder = lazy {
        S3Client.builder()
            .endpointOverride(endpointUri)
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
            // Fixed-length signed uploads avoid the S3-compatible server's streaming-trailer ambiguity.
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).chunkedEncodingEnabled(false).build())
            .httpClientBuilder(UrlConnectionHttpClient.builder().connectionTimeout(Duration.ofSeconds(2)).socketTimeout(Duration.ofSeconds(10)))
            .overrideConfiguration { it.apiCallTimeout(Duration.ofSeconds(15)).apiCallAttemptTimeout(Duration.ofSeconds(12)) }
            .build()
    }
    private val client: S3Client by clientHolder

    @PreDestroy
    override fun close() {
        if (clientHolder.isInitialized()) clientHolder.value.close()
    }

    override fun put(storedName: String, content: InputStream, sizeBytes: Long) {
        val request = PutObjectRequest.builder().bucket(bucket).key(key(storedName)).contentLength(sizeBytes)
            .checksumAlgorithm(ChecksumAlgorithm.SHA256).build()
        try {
            client.putObject(request, RequestBody.fromInputStream(content, sizeBytes))
        } catch (_: SdkException) {
            unavailable()
        }
    }

    override fun get(storedName: String): StoredFile? {
        val request = GetObjectRequest.builder().bucket(bucket).key(key(storedName)).build()
        return try {
            val response = client.getObject(request)
            val size = response.response().contentLength()
            if (size == null || size < 0) {
                response.close()
                unavailable()
            }
            StoredFile(size, response)
        } catch (_: NoSuchKeyException) {
            null
        } catch (_: SdkException) {
            unavailable()
        }
    }

    private fun key(name: String): String {
        require(name.isNotBlank() && name.length <= 255 && '/' !in name && '\\' !in name && '\u0000' !in name && name !in setOf(".", "..")) {
            "Неверное имя объекта документа"
        }
        return "documents/$name"
    }

    private fun unavailable(): Nothing = throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Хранилище документов временно недоступно. Попробуйте позже")
}
