package ru.creditcabinet.common.mail

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.mail.javamail.MimeMessageHelper
import java.net.InetAddress
import java.net.URI
import java.time.Duration
import java.util.UUID

@ConfigurationProperties("cabinet.mail")
data class MailSettings(
    val mode: String = "off",
    val host: String = "mailpit",
    val port: Int = 1025,
    val from: String = "cabinet@demo.test",
    val username: String = "",
    val password: String = "",
    val startTls: Boolean = false,
    val publicCabinetUrl: String = "http://localhost:8080",
) {
    init {
        require(mode in setOf("off", "on", "auto") && port in 1..65535) { "Invalid mail configuration" }
        val uri = URI(publicCabinetUrl)
        require(uri.scheme in setOf("http", "https") && uri.host != null && uri.rawUserInfo == null) { "Invalid public cabinet origin" }
        require(uri.rawQuery == null && uri.rawFragment == null && uri.path in setOf("", "/")) { "Public cabinet URL must be an origin" }
    }

    fun cabinetLink(path: String): String {
        require(path.matches(Regex("^/(?!/)[A-Za-z0-9/_\\-.~%?=&]{0,510}$"))) { "Mail link must stay inside the cabinet" }
        return URI(publicCabinetUrl).resolve(path).toASCIIString()
    }
}

/** Auto mode discovers the optional Compose service; a temporary SMTP outage must not turn off an enabled feature. */
class MailCapabilities(private val settings: MailSettings, private val resolve: (String) -> Boolean = { InetAddress.getAllByName(it).isNotEmpty() }) {
    @Volatile private var discovered = false

    @Volatile private var checkedAt = Long.MIN_VALUE

    fun enabled(): Boolean {
        if (settings.mode == "off") return false
        if (settings.mode == "on" || discovered) return true
        if (settings.host != "mailpit") return false
        val now = System.nanoTime()
        if (checkedAt != Long.MIN_VALUE && now - checkedAt < Duration.ofSeconds(2).toNanos()) return false
        synchronized(this) {
            if (discovered) return true
            discovered = runCatching { resolve(settings.host) }.getOrDefault(false)
            checkedAt = System.nanoTime()
            return discovered
        }
    }
}

fun interface MailTransport {
    fun send(message: OutgoingMail)
}

data class OutgoingMail(val recipient: String, val subject: String, val text: String, val key: String)

class SmtpMailTransport(private val sender: JavaMailSenderImpl, private val capabilities: MailCapabilities) : MailTransport {
    override fun send(message: OutgoingMail) {
        check(capabilities.enabled()) { "Mail channel is not configured" }
        val stableId = UUID.nameUUIDFromBytes(message.key.toByteArray(Charsets.UTF_8))
        val mime = StableMessage(sender.session, "<$stableId@cabinet.local>")
        MimeMessageHelper(mime, "UTF-8").apply {
            setTo(message.recipient)
            setFrom(sender.javaMailProperties.getProperty("cabinet.from"))
            setSubject(message.subject)
            setText(message.text, false)
        }
        sender.send(mime)
    }

    private class StableMessage(session: Session, private val identifier: String) : MimeMessage(session) {
        override fun updateMessageID() = setHeader("Message-ID", identifier)
    }
}

@Configuration
@EnableConfigurationProperties(MailSettings::class)
class MailSupportConfig {
    @Bean
    fun mailCapabilities(settings: MailSettings): MailCapabilities = MailCapabilities(settings)

    @Bean
    fun mailTransport(settings: MailSettings, capabilities: MailCapabilities): MailTransport {
        val sender = JavaMailSenderImpl().apply {
            host = settings.host
            port = settings.port
            username = settings.username
            password = settings.password
            defaultEncoding = "UTF-8"
            javaMailProperties.apply {
                setProperty("cabinet.from", settings.from)
                setProperty("mail.smtp.connectiontimeout", "2000")
                setProperty("mail.smtp.timeout", "5000")
                setProperty("mail.smtp.writetimeout", "5000")
                setProperty("mail.smtp.auth", settings.username.isNotBlank().toString())
                setProperty("mail.smtp.starttls.enable", settings.startTls.toString())
                setProperty("mail.smtp.starttls.required", settings.startTls.toString())
            }
        }
        return SmtpMailTransport(sender, capabilities)
    }
}
