package ru.creditcabinet.cataloggrpc

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Grpc
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.grpc.TlsServerCredentials
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.catalogjdbc.JdbcProductCatalog
import ru.creditcabinet.common.security.JwtVerifier
import java.io.File
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.time.Clock

@Configuration(proxyBeanMethods = false)
internal class CatalogGrpcServerConfiguration {
    @Bean(destroyMethod = "close")
    fun catalogDataSource(
        @Value("\${cabinet.catalog.grpc.database.url}") url: String,
        @Value("\${cabinet.catalog.grpc.database.user:catalog_app}") user: String,
        @Value("\${cabinet.catalog.grpc.database.password}") password: String,
    ): HikariDataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            maximumPoolSize = 5
            minimumIdle = 0
        },
    )

    @Bean(initMethod = "start", destroyMethod = "shutdown")
    fun catalogGrpcServer(
        dataSource: HikariDataSource,
        environment: Environment,
        @Value("\${JWT_SECRET}") jwtSecret: String,
        @Value("\${cabinet.catalog.grpc.server.host:127.0.0.1}") host: String,
        @Value("\${cabinet.catalog.grpc.server.port:9094}") port: Int,
        @Value("\${cabinet.catalog.grpc.server.certificate}") certificate: File,
        @Value("\${cabinet.catalog.grpc.server.key}") key: File,
        @Value("\${cabinet.catalog.grpc.server.client-ca}") clientCa: File,
        @Value("\${cabinet.catalog.grpc.server.client-uri:spiffe://creditcabinet/credit}") clientUri: String,
    ): Server {
        val jdbc = JdbcTemplate(dataSource)
        val ownDatabase = jdbc.queryForObject(
            """
            select current_database() = 'catalog'
                and has_table_privilege('public.products', 'SELECT')
                and has_table_privilege('public.product_versions', 'SELECT')
                and has_table_privilege('catalog_cutover.authority', 'SELECT')
                and not has_table_privilege('catalog_cutover.authority', 'UPDATE')
                and not pg_has_role((select relowner from pg_class where oid = 'public.products'::regclass), 'MEMBER')
                and not pg_has_role((select relowner from pg_class where oid = 'public.product_versions'::regclass), 'MEMBER')
            """.trimIndent(),
            Boolean::class.java,
        )
        check(ownDatabase == true) { "Catalog requires a restricted runtime role in its own database" }
        val clock = Clock.systemUTC()
        val service = CatalogGrpcService(
            JdbcProductCatalog(jdbc, clock),
            jdbc,
            TransactionTemplate(DataSourceTransactionManager(dataSource)),
            JwtVerifier(jwtSecret, environment),
            clock,
        )
        val credentials = TlsServerCredentials.newBuilder()
            .keyManager(certificate, key)
            .trustManager(clientCa)
            .clientAuth(TlsServerCredentials.ClientAuth.REQUIRE)
            .build()
        return NettyServerBuilder.forAddress(InetSocketAddress(host, port), credentials)
            .intercept(CatalogPeer(clientUri))
            .addService(service)
            .build()
    }
}

internal class CatalogPeer(private val clientUri: String) : ServerInterceptor {
    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>,
    ): ServerCall.Listener<ReqT> {
        val allowed = runCatching {
            val session = call.attributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION)
            val peer = session?.peerCertificates?.firstOrNull() as? X509Certificate
            peer?.subjectAlternativeNames?.any { it.size >= 2 && it[0] == 6 && it[1] == clientUri } == true
        }.getOrDefault(false)
        if (!allowed) {
            call.close(Status.PERMISSION_DENIED, Metadata())
            return object : ServerCall.Listener<ReqT>() {}
        }
        return Contexts.interceptCall(withCatalogBearer(headers), call, headers, next)
    }
}

internal fun withCatalogBearer(headers: Metadata): Context {
    val authorization = headers.getAll(catalogAuthorization)?.singleOrNull()?.takeIf { it.startsWith("Bearer ") }
    return Context.current().withValue(catalogBearer, authorization)
}
