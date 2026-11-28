package ru.creditcabinet.mcp.http

import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.time.Duration
import java.util.Collections
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.HtmlUtils
import ru.creditcabinet.auth.application.AuthService
import ru.creditcabinet.common.limits.LimitWindow
import ru.creditcabinet.common.limits.SharedLimits
import ru.creditcabinet.identity.domain.IdentityFailure
import ru.creditcabinet.mcp.config.McpOAuthSettings
import ru.creditcabinet.mcp.config.McpOAuthSettings.Companion.AUTHORIZE_PATH
import ru.creditcabinet.mcp.config.McpOAuthSettings.Companion.OPTIONS_PATH
import ru.creditcabinet.mcp.config.McpOAuthSettings.Companion.REVOCATION_PATH
import ru.creditcabinet.mcp.config.McpOAuthSettings.Companion.SCOPE
import ru.creditcabinet.mcp.config.McpOAuthSettings.Companion.TOKEN_PATH
import ru.creditcabinet.mcp.config.newMcpSecret
import ru.creditcabinet.mcp.config.opaqueSecret
import ru.creditcabinet.mcp.config.pkceVerifier
import ru.creditcabinet.mcp.persistence.McpOAuthStore
import ru.creditcabinet.user.domain.UserRole

@Hidden
@RestController
@ConditionalOnProperty("cabinet.mcp-oauth.enabled", havingValue = "true")
internal class McpOAuthController(
    private val settings: McpOAuthSettings,
    private val store: McpOAuthStore,
    private val auth: AuthService,
    private val limits: SharedLimits,
) {
    @GetMapping(OPTIONS_PATH)
    fun metadata(): ResponseEntity<*> = response(HttpStatus.OK).body(
        mapOf(
            "issuer" to settings.issuer,
            "authorization_endpoint" to settings.issuer + AUTHORIZE_PATH,
            "token_endpoint" to settings.issuer + TOKEN_PATH,
            "revocation_endpoint" to settings.issuer + REVOCATION_PATH,
            "response_types_supported" to listOf("code"),
            "grant_types_supported" to listOf("authorization_code"),
            "token_endpoint_auth_methods_supported" to listOf("none"),
            "revocation_endpoint_auth_methods_supported" to listOf("none"),
            "scopes_supported" to listOf(SCOPE),
            "code_challenge_methods_supported" to listOf("S256"),
            "client_id_metadata_document_supported" to false,
            "authorization_response_iss_parameter_supported" to true,
        ),
    )

    @GetMapping(AUTHORIZE_PATH)
    fun start(request: HttpServletRequest): ResponseEntity<*> {
        if (!registered(request)) return problem("invalid_request")
        val state = request.one("state")?.takeIf { it.length in 1..512 && it.all { ch -> ch in '!'..'~' } }
            ?: return problem("invalid_request")
        if (request.one("response_type") != "code") return callback("error", "unsupported_response_type", state)
        if (request.one("resource") != settings.resource) return callback("error", "invalid_target", state)
        if (request.one("scope") != SCOPE) return callback("error", "invalid_scope", state)
        val challenge = request.one("code_challenge")?.takeIf(opaqueSecret::matches)
        if (request.one("code_challenge_method") != "S256" || challenge == null) return callback("error", "invalid_request", state)
        limit(request, "mcp-oauth-start")
        val flow = newMcpSecret()
        val csrf = newMcpSecret()
        store.start(flow, csrf, state, challenge)
        return consentPage(csrf, HttpStatus.OK, flow = flow)
    }

    @PostMapping(AUTHORIZE_PATH, consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun decide(request: HttpServletRequest): ResponseEntity<*> {
        if (request.queryString != null || request.uniqueHeader(HttpHeaders.ORIGIN) != settings.issuer) {
            return problem("invalid_request")
        }
        val flow = request.cookies?.singleOrNull { it.name == FLOW_COOKIE }?.value?.takeIf(opaqueSecret::matches)
            ?: return problem("invalid_request")
        val csrf = request.one("csrf")?.takeIf(opaqueSecret::matches) ?: return problem("invalid_request")
        val state = store.pendingState(flow, csrf) ?: return problem("invalid_request")
        if (request.one("decision") == "deny") {
            if (!store.deny(flow, csrf)) return problem("invalid_request")
            return callback("error", "access_denied", state, clearCookie = true)
        }
        if (request.one("decision") != "approve") return problem("invalid_request")
        val email = request.one("email")?.takeIf { it.isNotBlank() && it.length <= 255 } ?: return problem("invalid_request")
        val password = request.one("password")?.takeIf { it.isNotBlank() && it.length <= 256 } ?: return problem("invalid_request")
        val user = try {
            auth.authenticateUser(email, password, request.clientAddress())
        } catch (_: IdentityFailure.Unauthorized) {
            return consentPage(csrf, HttpStatus.UNAUTHORIZED, "Проверьте адрес электронной почты и пароль.")
        } catch (_: IdentityFailure.Forbidden) {
            return consentPage(csrf, HttpStatus.FORBIDDEN, "Доступ разрешён только действующим агентам.")
        }
        if (user.role != UserRole.AGENT) return consentPage(csrf, HttpStatus.FORBIDDEN, "Доступ разрешён только действующим агентам.")
        val code = newMcpSecret()
        if (!store.approve(flow, csrf, code, user)) return problem("invalid_request")
        return callback("code", code, state, clearCookie = true)
    }

    @PostMapping(TOKEN_PATH, consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun token(request: HttpServletRequest): ResponseEntity<*> {
        if (request.queryString != null ||
            request.getHeader(HttpHeaders.AUTHORIZATION) != null ||
            request.getParameterValues("client_secret") != null
        ) {
            return problem("invalid_request")
        }
        if (request.one("client_id") != settings.clientId) return problem("invalid_client")
        if (!registered(request)) return problem("invalid_grant")
        if (request.one("grant_type") != "authorization_code") return problem("unsupported_grant_type")
        if (request.one("resource") != settings.resource) return problem("invalid_target")
        val verifier = request.one("code_verifier")?.takeIf(pkceVerifier::matches) ?: return problem("invalid_grant")
        val code = request.one("code")?.takeIf(opaqueSecret::matches) ?: return problem("invalid_grant")
        limit(request, "mcp-oauth-token")
        val access = ACCESS_PREFIX + newMcpSecret()
        if (!store.exchange(code, verifier, access)) return problem("invalid_grant")
        return response(HttpStatus.OK).body(
            mapOf(
                "access_token" to access,
                "token_type" to "Bearer",
                "expires_in" to McpOAuthSettings.ACCESS_LIFETIME_SECONDS,
                "scope" to SCOPE,
            ),
        )
    }

    @PostMapping(REVOCATION_PATH, consumes = [MediaType.APPLICATION_FORM_URLENCODED_VALUE])
    fun revoke(request: HttpServletRequest): ResponseEntity<*> {
        if (request.queryString != null ||
            request.getHeader(HttpHeaders.AUTHORIZATION) != null ||
            request.getParameterValues("client_secret") != null
        ) {
            return problem("invalid_request")
        }
        if (request.one("client_id") != settings.clientId) return problem("invalid_client")
        val token = request.one("token")?.takeIf { it.length in 1..512 } ?: return problem("invalid_request")
        limit(request, "mcp-oauth-token")
        if (isSecret(token, ACCESS_PREFIX)) store.revoke(token)
        return response(HttpStatus.OK).build<Void>()
    }

    @PostMapping("/internal/mcp/authorize", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun authorize(request: HttpServletRequest, @RequestBody body: AccessRequest): ResponseEntity<*> {
        if (!settings.authenticatesMcp(request.uniqueHeader(HttpHeaders.AUTHORIZATION)) || !isSecret(body.accessToken, ACCESS_PREFIX)) {
            return unauthorized()
        }
        val assertion = ASSERTION_PREFIX + newMcpSecret()
        val actor = store.authorize(body.accessToken, assertion) ?: return unauthorized()
        return response(HttpStatus.OK).body(
            mapOf(
                "actorId" to actor,
                "role" to UserRole.AGENT.name,
                "resource" to settings.resource,
                "scope" to SCOPE,
                "actorAssertion" to assertion,
            ),
        )
    }

    @PostMapping("/internal/mcp/redeem", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun redeem(request: HttpServletRequest, @RequestBody body: AssertionRequest): ResponseEntity<*> {
        if (!settings.authenticatesCredit(request.uniqueHeader(HttpHeaders.AUTHORIZATION)) || !isSecret(body.actorAssertion, ASSERTION_PREFIX)) {
            return unauthorized()
        }
        val actor = store.redeem(body.actorAssertion) ?: return unauthorized()
        return response(HttpStatus.OK).body(mapOf("actorId" to actor, "role" to UserRole.AGENT.name))
    }

    data class AccessRequest(val accessToken: String)
    data class AssertionRequest(val actorAssertion: String)

    private fun registered(request: HttpServletRequest): Boolean =
        request.one("client_id") == settings.clientId && request.one("redirect_uri") == settings.redirectUri

    private fun limit(request: HttpServletRequest, scope: String) {
        if (limits.enabled) {
            limits.acquire(
                "Слишком много запросов. Попробуйте через минуту.",
                LimitWindow(scope, request.clientAddress(), 60, Duration.ofMinutes(1)),
            )
        }
    }

    private fun callback(field: String, value: String, state: String, clearCookie: Boolean = false): ResponseEntity<*> {
        val query = listOf(field to value, "state" to state, "iss" to settings.issuer)
            .joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, UTF_8)}" }
        val response = response(HttpStatus.SEE_OTHER).location(URI("${settings.redirectUri}?$query"))
        if (clearCookie) response.header(HttpHeaders.SET_COOKIE, cookie("", 0))
        return response.build<Void>()
    }

    private fun consentPage(csrf: String, status: HttpStatus, error: String = "", flow: String? = null): ResponseEntity<*> {
        val client = HtmlUtils.htmlEscape(settings.clientName)
        val message = if (error.isEmpty()) "" else "<p role=\"alert\">${HtmlUtils.htmlEscape(error)}</p>"
        val response = response(status).contentType(MediaType("text", "html", UTF_8))
        if (flow != null) response.header(HttpHeaders.SET_COOKIE, cookie(flow, McpOAuthSettings.BROWSER_LIFETIME_SECONDS))
        return response.body(
            """
            <!doctype html>
            <html lang="ru">
            <head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
            <title>Доступ к кабинету агента</title></head>
            <body><main>
              <h1>Разрешить доступ приложению «$client»?</h1>
              <p>Приложение сможет просматривать закреплённые за вами компании и сведения об их заявках.
              Изменять заявки, подписывать документы и отправлять данные от вашего имени оно не сможет.</p>
              $message
              <form method="post" action="$AUTHORIZE_PATH">
                <input type="hidden" name="csrf" value="$csrf">
                <p><label>Электронная почта агента <input type="email" name="email" maxlength="255" autocomplete="username" required></label></p>
                <p><label>Пароль <input type="password" name="password" maxlength="256" autocomplete="current-password" required></label></p>
                <button type="submit" name="decision" value="approve">Войти и разрешить чтение</button>
              </form>
              <form method="post" action="$AUTHORIZE_PATH">
                <input type="hidden" name="csrf" value="$csrf">
                <button type="submit" name="decision" value="deny">Отказать</button>
              </form>
            </main></body></html>
            """.trimIndent(),
        )
    }

    private fun cookie(value: String, seconds: Int): String = ResponseCookie.from(FLOW_COOKIE, value)
        .httpOnly(true).secure(true).sameSite("Lax").path("/").maxAge(seconds.toLong()).build().toString()

    private fun problem(error: String): ResponseEntity<*> = response(HttpStatus.BAD_REQUEST).body(mapOf("error" to error))

    private fun unauthorized(): ResponseEntity<*> = response(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "invalid_token"))

    private fun response(status: HttpStatus): ResponseEntity.BodyBuilder = ResponseEntity.status(status)
        .header(HttpHeaders.CACHE_CONTROL, "no-store")
        .header(HttpHeaders.PRAGMA, "no-cache")
        .header("Referrer-Policy", "same-origin")
        .header(
            "Content-Security-Policy",
            // Chromium checks the form-action policy again after the consent form's 303 redirect.
            "default-src 'none'; base-uri 'none'; form-action 'self' ${settings.redirectOrigin}; frame-ancestors 'none'",
        )

    private fun HttpServletRequest.one(name: String): String? = getParameterValues(name)?.singleOrNull()

    private fun HttpServletRequest.uniqueHeader(name: String): String? = Collections.list(getHeaders(name)).singleOrNull()

    private fun HttpServletRequest.clientAddress(): String = uniqueHeader("X-Real-IP")?.take(128) ?: remoteAddr

    private fun isSecret(value: String, prefix: String): Boolean =
        value.startsWith(prefix) && opaqueSecret.matches(value.substring(prefix.length))

    private companion object {
        const val FLOW_COOKIE = "__Host-cabinet-mcp-oauth"
        const val ACCESS_PREFIX = "mcp_at_"
        const val ASSERTION_PREFIX = "mcp_ca_"
    }
}
