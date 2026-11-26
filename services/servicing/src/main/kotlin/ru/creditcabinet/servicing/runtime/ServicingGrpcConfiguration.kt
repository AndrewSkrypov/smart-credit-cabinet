@file:Suppress("ktlint:standard:no-wildcard-imports")

package ru.creditcabinet.servicing.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import io.grpc.*
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.servicing.core.ServicingEngine
import ru.creditcabinet.servicing.grpc.servicingAuthorization
import java.io.File
import java.net.InetSocketAddress
import java.security.cert.X509Certificate

@Configuration
class ServicingGrpcConfiguration {
    @Bean(initMethod="start",destroyMethod="shutdown")
    fun servicingGrpcServer(engine: ServicingEngine, verifier: JwtVerifier, mapper: ObjectMapper,
        @Value("\${cabinet.servicing.grpc.host:127.0.0.1}") host: String,
        @Value("\${cabinet.servicing.grpc.port:9095}") port: Int,
        @Value("\${cabinet.servicing.grpc.certificate}") certificate: File,
        @Value("\${cabinet.servicing.grpc.key}") key: File,
        @Value("\${cabinet.servicing.grpc.client-ca}") clientCa: File,
        @Value("\${cabinet.servicing.grpc.client-uri:spiffe://creditcabinet/credit}") clientUri: String): Server {
        val credentials = TlsServerCredentials.newBuilder().keyManager(certificate,key).trustManager(clientCa).clientAuth(TlsServerCredentials.ClientAuth.REQUIRE).build()
        return NettyServerBuilder.forAddress(InetSocketAddress(host,port),credentials).intercept(ServicingPeer(clientUri))
            .addService(ServicingGrpcService(engine,verifier,mapper)).build()
    }
}
class ServicingPeer(private val clientUri: String) : ServerInterceptor {
    override fun <ReqT,RespT> interceptCall(call: ServerCall<ReqT,RespT>, headers: Metadata, next: ServerCallHandler<ReqT,RespT>): ServerCall.Listener<ReqT> {
        val allowed = runCatching {
            val peer = call.attributes.get(Grpc.TRANSPORT_ATTR_SSL_SESSION)?.peerCertificates?.firstOrNull() as? X509Certificate
            peer?.subjectAlternativeNames?.any { it.size >= 2 && it[0] == 6 && it[1] == clientUri } == true
        }.getOrDefault(false)
        if(!allowed) { call.close(Status.PERMISSION_DENIED,Metadata()); return object : ServerCall.Listener<ReqT>() {} }
        val bearer = headers.getAll(servicingAuthorization)?.singleOrNull()?.takeIf { it.startsWith("Bearer ") }
        return Contexts.interceptCall(Context.current().withValue(servicingBearer,bearer),call,headers,next)
    }
}
