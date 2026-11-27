package ru.creditcabinet.cataloggrpc

import io.grpc.Server
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Import

@SpringBootConfiguration(proxyBeanMethods = false)
@Import(CatalogGrpcServerConfiguration::class)
internal class CatalogGrpcApplication

fun main(args: Array<String>) {
    val context = SpringApplicationBuilder(CatalogGrpcApplication::class.java)
        .web(WebApplicationType.NONE)
        .run(*args)
    context.getBean(Server::class.java).awaitTermination()
}
