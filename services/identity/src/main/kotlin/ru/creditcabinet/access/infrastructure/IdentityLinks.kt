package ru.creditcabinet.access.infrastructure

import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import ru.creditcabinet.access.domain.AccessGrant
import ru.creditcabinet.access.domain.AccountLinkIssuer
import ru.creditcabinet.access.domain.LinkPurpose
import ru.creditcabinet.common.mail.MailSettings
import java.net.URI
import java.util.Base64
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@Component
class IdentityLinks(
    @Value("\${cabinet.auth.link-key:dev-identity-links-change-before-deployment-48bytes}") private val key: String,
    @Value("\${cabinet.auth.link-key-version:1}") override val keyVersion: String,
    @Value("\${cabinet.auth.jwt-secret}") jwtSecret: String,
    environment: Environment,
    private val settings: MailSettings,
) : AccountLinkIssuer {
    init {
        require(key.toByteArray(Charsets.UTF_8).size >= 32 && key != jwtSecret) { "Identity link key must be separate and at least 32 bytes" }
        require(keyVersion.matches(Regex("[A-Za-z0-9_-]{1,32}"))) { "Invalid identity link key version" }
        require("prod" !in environment.activeProfiles || key != "dev-identity-links-change-before-deployment-48bytes") {
            "Set IDENTITY_LINK_KEY before deploying"
        }
        if ("prod" in environment.activeProfiles) {
            require(URI(settings.publicCabinetUrl).scheme == "https") { "Public cabinet origin must use HTTPS in production" }
            require(key != environment.getProperty("spring.datasource.password") && key != environment.getProperty("cabinet.registry.api-key")) {
                "Identity link key must be separate from database and simulator credentials"
            }
        }
    }

    fun token(purpose: LinkPurpose, id: UUID): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal("${purpose.name}:$id".toByteArray(Charsets.UTF_8)))
        return "$id.$signature"
    }

    override fun hash(purpose: LinkPurpose, id: UUID): String = AccessGrant.hash(token(purpose, id))

    override fun link(purpose: LinkPurpose, id: UUID): String = settings.cabinetLink(purpose.path) + "#token=" + token(purpose, id)
}
