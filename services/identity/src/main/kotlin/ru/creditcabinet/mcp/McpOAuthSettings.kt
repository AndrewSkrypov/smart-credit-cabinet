package ru.creditcabinet.mcp

import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.net.URI
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

@Component
@ConditionalOnProperty("cabinet.mcp-oauth.enabled", havingValue = "true")
class McpOAuthSettings(
    @Value("\${cabinet.mcp-oauth.issuer}") val issuer: String,
    @Value("\${cabinet.mcp-oauth.client-id}") val clientId: String,
    @Value("\${cabinet.mcp-oauth.client-name}") val clientName: String,
    @Value("\${cabinet.mcp-oauth.redirect-uri}") val redirectUri: String,
    @Value("\${cabinet.mcp-oauth.identity-service-token}") identityServiceToken: String,
    @Value("\${cabinet.mcp-oauth.credit-service-token}") creditServiceToken: String,
    @Value("\${cabinet.auth.jwt-secret}") browserJwtSecret: String,
) {
    val resource = "$issuer/api/mcp"
    private val identityDigest = digestBytes(identityServiceToken)
    private val creditDigest = digestBytes(creditServiceToken)

    init {
        val origin = URI(issuer)
        require(
            issuer.length <= 280 &&
                origin.scheme == "https" &&
                origin.host != null &&
                origin.port == -1 &&
                origin.rawUserInfo == null &&
                origin.rawPath.isEmpty() &&
                origin.rawQuery == null &&
                origin.rawFragment == null,
        ) { "MCP OAuth issuer must be a canonical HTTPS origin" }
        require(clientId.matches(Regex("[A-Za-z0-9._~:-]{1,255}"))) { "Invalid MCP OAuth client ID" }
        require(clientName.isNotBlank() && clientName.length <= 100 && clientName.none(Char::isISOControl)) { "Invalid MCP OAuth client name" }
        val redirect = URI(redirectUri)
        val loopback = redirect.host in setOf("127.0.0.1", "[::1]") && redirect.port in 1..65535
        require(
            redirectUri.length <= 1000 &&
                redirect.host != null &&
                redirect.rawUserInfo == null &&
                redirect.rawFragment == null &&
                redirect.rawQuery == null &&
                !redirect.rawPath.isNullOrBlank() &&
                (redirect.scheme == "https" || redirect.scheme == "http" && loopback),
        ) { "MCP OAuth redirect must be an exact HTTPS URI or a numeric HTTP loopback URI with a host port" }
        require(
            identityServiceToken.matches(SERVICE_TOKEN) &&
                creditServiceToken.matches(SERVICE_TOKEN) &&
                identityServiceToken != creditServiceToken &&
                identityServiceToken != browserJwtSecret &&
                creditServiceToken != browserJwtSecret,
        ) { "MCP callers require distinct random service tokens separate from the browser JWT key" }
    }

    fun authenticatesMcp(authorization: String?) = authenticates(authorization, identityDigest)

    fun authenticatesCredit(authorization: String?) = authenticates(authorization, creditDigest)

    private fun authenticates(authorization: String?, expected: ByteArray): Boolean {
        val token = authorization?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7) ?: return false
        return token.matches(SERVICE_TOKEN) && MessageDigest.isEqual(digestBytes(token), expected)
    }

    companion object {
        const val OPTIONS_PATH = "/.well-known/oauth-authorization-server"
        const val AUTHORIZE_PATH = "/api/mcp/oauth/authorize"
        const val TOKEN_PATH = "/api/mcp/oauth/token"
        const val REVOCATION_PATH = "/api/mcp/oauth/revoke"
        const val SCOPE = "cabinet:mcp:read"
        const val ACCESS_LIFETIME_SECONDS = 900
        const val BROWSER_LIFETIME_SECONDS = 600
        private val SERVICE_TOKEN = Regex("[A-Za-z0-9_-]{32,128}")
    }
}

private val secureRandom = SecureRandom()
private val base64Url = Base64.getUrlEncoder().withoutPadding()
internal val opaqueSecret = Regex("[A-Za-z0-9_-]{43}")
internal val pkceVerifier = Regex("[A-Za-z0-9._~-]{43,128}")

internal fun newMcpSecret(): String = base64Url.encodeToString(ByteArray(32).also(secureRandom::nextBytes))

internal fun pkceChallenge(verifier: String): String = base64Url.encodeToString(digestBytes(verifier))

internal fun digest(value: String): String = HexFormat.of().formatHex(digestBytes(value))

private fun digestBytes(value: String): ByteArray = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(StandardCharsets.UTF_8))
