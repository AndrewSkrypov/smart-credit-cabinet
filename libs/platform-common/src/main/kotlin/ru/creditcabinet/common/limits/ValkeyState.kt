package ru.creditcabinet.common.limits

import io.valkey.ConnectionPoolConfig
import io.valkey.DefaultJedisClientConfig
import io.valkey.HostAndPort
import io.valkey.JedisPooled
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import java.time.Duration
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ConfigurationProperties("cabinet.valkey")
class ValkeyProperties(
    val enabled: Boolean = false,
    val host: String = "localhost",
    val port: Int = 6379,
    val password: String = "",
    val connectTimeout: Duration = Duration.ofMillis(300),
    val readTimeout: Duration = Duration.ofMillis(500),
    val poolWait: Duration = Duration.ofMillis(200),
    val connections: Int = 16,
) {
    init {
        require(port in 1..65535 && connections in 1..64)
        require(listOf(connectTimeout, readTimeout, poolWait).all { it.toMillis() in 1..2000 })
        require(!enabled || (host.isNotBlank() && password.isNotBlank())) { "Valkey requires a private host and password" }
    }
}

/** Bounded connection pool, no retries for non-idempotent acquisitions and no secret logging. */
class ValkeyState(val properties: ValkeyProperties, secret: String) : AutoCloseable {
    val enabled: Boolean get() = properties.enabled
    private val keySecret = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")
    private val client = if (enabled) {
        val pool = ConnectionPoolConfig().apply {
            maxTotal = properties.connections
            maxIdle = properties.connections
            minIdle = 0
            setMaxWait(properties.poolWait)
            blockWhenExhausted = true
            // Validate before sending a quota command: a stale socket may be replaced safely,
            // whereas retrying EVAL after a lost response could count the attempt twice.
            testOnBorrow = true
            testWhileIdle = false
        }
        val config = DefaultJedisClientConfig.builder().password(properties.password)
            .connectionTimeoutMillis(properties.connectTimeout.toMillis().toInt())
            .socketTimeoutMillis(properties.readTimeout.toMillis().toInt())
            .blockingSocketTimeoutMillis(properties.readTimeout.toMillis().toInt()).build()
        JedisPooled(pool, HostAndPort(properties.host, properties.port), config)
    } else {
        null
    }

    // A signing-secret rotation on restore also changes this namespace. No email, IP or user
    // id is stored in key names. Mac is not thread safe, so create one instance per operation.
    fun key(scope: String, subject: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(keySecret) }
        val hash = mac.doFinal("cabinet-v1\u0000$scope\u0000$subject".toByteArray(Charsets.UTF_8))
        return "cabinet:v1:$scope:" + hash.joinToString("") { "%02x".format(it) }
    }

    fun eval(script: String, keys: List<String>, arguments: List<String>): Any? = checkNotNull(client).eval(script, keys, arguments)
    fun read(key: String): String? = checkNotNull(client).get(key)
    fun remove(key: String) {
        checkNotNull(client).del(key)
    }
    override fun close() {
        client?.close()
    }
}

@Configuration
@EnableConfigurationProperties(ValkeyProperties::class)
class ValkeyConfig {
    @Bean(destroyMethod = "close")
    fun valkeyState(
        properties: ValkeyProperties,
        @Value("\${cabinet.auth.jwt-secret}") secret: String,
        environment: Environment,
    ): ValkeyState {
        if (environment.activeProfiles.contains("prod")) {
            require(properties.enabled && properties.password.length >= 32 && !properties.password.startsWith("dev-")) {
                "Production requires enabled Valkey with a generated password of at least 32 characters"
            }
        }
        return ValkeyState(properties, secret)
    }
}
