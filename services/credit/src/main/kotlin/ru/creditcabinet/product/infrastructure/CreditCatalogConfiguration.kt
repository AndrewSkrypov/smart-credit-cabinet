package ru.creditcabinet.product.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import io.grpc.ManagedChannel
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import ru.creditcabinet.cataloggrpc.CatalogGrpcClient
import ru.creditcabinet.cataloggrpc.catalogTlsChannel
import ru.creditcabinet.catalogjdbc.JdbcProductCatalog
import java.io.File
import java.time.Clock

@Configuration(proxyBeanMethods = false)
internal class CreditCatalogConfiguration {
    @Bean
    fun creditProductCatalog(
        jdbc: JdbcTemplate,
        clock: Clock,
        @Value("\${cabinet.catalog.mode:local}") mode: String,
    ): JdbcProductCatalog {
        require(mode in setOf("local", "remote")) { "cabinet.catalog.mode must be local or remote" }
        if (mode == "local" &&
            jdbc.queryForObject("select to_regclass('catalog_cutover.authority') is not null", Boolean::class.java) == true
        ) {
            check(jdbc.queryForObject("select writable from catalog_cutover.authority where singleton", Boolean::class.java) == true) {
                "Local Catalog cannot start while the Credit writer is fenced"
            }
        }
        return JdbcProductCatalog(jdbc, clock)
    }
}

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "cabinet.catalog", name = ["mode"], havingValue = "remote")
internal class RemoteCatalogConfiguration {
    @Bean(destroyMethod = "shutdownNow")
    fun catalogChannel(
        @Value("\${cabinet.catalog.grpc.client.host}") host: String,
        @Value("\${cabinet.catalog.grpc.client.port:9094}") port: Int,
        @Value("\${cabinet.catalog.grpc.client.certificate}") certificate: File,
        @Value("\${cabinet.catalog.grpc.client.key}") key: File,
        @Value("\${cabinet.catalog.grpc.client.server-ca}") serverCa: File,
    ): ManagedChannel = catalogTlsChannel(host, port, certificate, key, serverCa)

    @Bean
    @Primary
    fun remoteProductCatalog(
        catalogChannel: ManagedChannel,
        creditProductCatalog: JdbcProductCatalog,
        jdbc: JdbcTemplate,
        mapper: ObjectMapper,
        @Value("\${cabinet.catalog.grpc.client.deadline-ms:1500}") deadlineMs: Long,
    ): RemoteProductCatalog {
        check(jdbc.queryForObject("select catalog_cutover.credit_replay_ready()", Boolean::class.java) == true) {
            "Remote Catalog requires the Credit writer to be fenced and a restricted database role"
        }
        return RemoteProductCatalog(CatalogGrpcClient(catalogChannel, deadlineMs), creditProductCatalog, CatalogRevisionCache(jdbc, mapper))
    }
}
