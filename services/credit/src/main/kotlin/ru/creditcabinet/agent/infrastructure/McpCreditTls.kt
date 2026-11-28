package ru.creditcabinet.agent.infrastructure

import org.apache.catalina.connector.Connector
import org.apache.coyote.http11.Http11NioProtocol
import org.apache.tomcat.util.net.SSLHostConfig
import org.apache.tomcat.util.net.SSLHostConfigCertificate
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.server.WebServerFactoryCustomizer
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.cert.CertificateFactory

@Component
@Profile("agent-mcp-remote")
internal class McpCreditTls(
    @Value("\${cabinet.mcp.credit-port:8443}") private val port: Int,
    @Value("\${cabinet.mcp.credit-tls.cert}") private val cert: Path,
    @Value("\${cabinet.mcp.credit-tls.key}") private val key: Path,
    @Value("\${cabinet.mcp.credit-tls.peer-ca}") private val ca: Path,
) : WebServerFactoryCustomizer<TomcatServletWebServerFactory> {
    override fun customize(factory: TomcatServletWebServerFactory) {
        require(port in 1024..65535)
        require(listOf(cert, key, ca).all { it.isAbsolute && Files.isRegularFile(it) && Files.isReadable(it) })
        val connector = Connector(Http11NioProtocol::class.java.name)
        connector.port = port
        connector.scheme = "https"
        connector.secure = true
        (connector.protocolHandler as Http11NioProtocol).setSSLEnabled(true)
        val host = SSLHostConfig()
        host.setProtocols("TLSv1.2,TLSv1.3")
        host.setCertificateVerification("required")
        host.setTrustStore(pemTrustStore(ca))
        val certificate = SSLHostConfigCertificate(host, SSLHostConfigCertificate.Type.UNDEFINED)
        certificate.certificateFile = cert.toString()
        certificate.certificateKeyFile = key.toString()
        host.addCertificate(certificate)
        connector.addSslHostConfig(host)
        factory.addAdditionalTomcatConnectors(connector)
    }
}

internal fun pemTrustStore(path: Path): KeyStore {
    require(path.isAbsolute && Files.isRegularFile(path) && Files.isReadable(path))
    val certificates = Files.newInputStream(path).use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
    require(certificates.isNotEmpty())
    return KeyStore.getInstance("PKCS12").apply {
        load(null, null)
        certificates.forEachIndexed { index, certificate -> setCertificateEntry("ca-$index", certificate) }
    }
}
