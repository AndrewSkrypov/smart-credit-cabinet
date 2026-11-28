package ru.creditcabinet.mcp.persistence

import java.util.UUID
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.creditcabinet.mcp.config.McpOAuthSettings
import ru.creditcabinet.mcp.config.digest
import ru.creditcabinet.mcp.config.pkceChallenge
import ru.creditcabinet.user.domain.User

@Component
@ConditionalOnProperty("cabinet.mcp-oauth.enabled", havingValue = "true")
internal class McpOAuthStore(
    private val jdbc: NamedParameterJdbcTemplate,
    private val settings: McpOAuthSettings,
) {
    private val registration = mapOf(
        "clientId" to settings.clientId,
        "redirectUri" to settings.redirectUri,
        "resource" to settings.resource,
        "scope" to McpOAuthSettings.SCOPE,
    )

    fun start(flow: String, csrf: String, state: String, challenge: String) {
        jdbc.update(
            """
            insert into mcp_oauth_flows (flow_hash, csrf_hash, client_id, redirect_uri, resource, scope,
                                       oauth_state, pkce_challenge, expires_at)
            values (:flow, :csrf, :clientId, :redirectUri, :resource, :scope, :state, :challenge,
                    statement_timestamp() + :ttl * interval '1 second')
            """.trimIndent(),
            registration + browser(flow, csrf) + mapOf(
                "state" to state,
                "challenge" to challenge,
                "ttl" to McpOAuthSettings.BROWSER_LIFETIME_SECONDS,
            ),
        )
    }

    fun pendingState(flow: String, csrf: String): String? = jdbc.query(
        """
        select oauth_state from mcp_oauth_flows
        where $PENDING_FLOW
        """.trimIndent(),
        registration + browser(flow, csrf),
    ) { row, _ -> row.getString("oauth_state") }.singleOrNull()

    fun deny(flow: String, csrf: String): Boolean = jdbc.update(
        "delete from mcp_oauth_flows where $PENDING_FLOW",
        registration + browser(flow, csrf),
    ) == 1

    fun approve(flow: String, csrf: String, code: String, user: User): Boolean = jdbc.update(
        """
        update mcp_oauth_flows
        set code_hash = :code, user_id = :userId, credentials_version = :version,
            expires_at = statement_timestamp() + interval '90 seconds'
        where $PENDING_FLOW and exists (
            select 1 from users u where u.id = :userId and u.credentials_version = :version
              and u.role = 'AGENT' and u.status = 'ACTIVE'
        )
        """.trimIndent(),
        registration + browser(flow, csrf) + mapOf(
            "code" to digest(code),
            "userId" to user.id,
            "version" to user.credentialsVersion,
        ),
    ) == 1

    /** PKCE is checked in the same atomic statement that consumes the code and issues the access grant. */
    fun exchange(code: String, verifier: String, access: String): Boolean = jdbc.update(
        """
        with consumed as (
            delete from mcp_oauth_flows f using users u
            where f.code_hash = :code and f.client_id = :clientId and f.redirect_uri = :redirectUri
              and f.resource = :resource and f.scope = :scope and f.pkce_challenge = :challenge
              and f.expires_at > statement_timestamp() and u.id = f.user_id
              and u.credentials_version = f.credentials_version and u.role = 'AGENT' and u.status = 'ACTIVE'
            returning f.user_id, f.credentials_version
        )
        insert into mcp_oauth_access (token_hash, client_id, resource, scope, user_id, credentials_version, expires_at)
        select :access, :clientId, :resource, :scope, user_id, credentials_version,
               statement_timestamp() + :ttl * interval '1 second' from consumed
        """.trimIndent(),
        registration + mapOf(
            "code" to digest(code),
            "challenge" to pkceChallenge(verifier),
            "access" to digest(access),
            "ttl" to McpOAuthSettings.ACCESS_LIFETIME_SECONDS,
        ),
    ) == 1

    fun revoke(access: String) {
        jdbc.update(
            "delete from mcp_oauth_access where token_hash = :access and client_id = :clientId and resource = :resource and scope = :scope",
            registration + mapOf("access" to digest(access)),
        )
    }

    /** An assertion inherits the active access grant and must be redeemed by Credit within 30 seconds. */
    fun authorize(access: String, assertion: String): UUID? = jdbc.query(
        """
        with issued as (
            insert into mcp_oauth_assertions (assertion_hash, access_hash, expires_at)
            select :assertion, t.token_hash, statement_timestamp() + interval '30 seconds'
            from mcp_oauth_access t join users u on u.id = t.user_id
            where t.token_hash = :access and $CURRENT_ACCESS
            returning access_hash
        )
        select t.user_id from issued join mcp_oauth_access t on t.token_hash = issued.access_hash
        """.trimIndent(),
        registration + mapOf("access" to digest(access), "assertion" to digest(assertion)),
    ) { row, _ -> row.getObject("user_id", UUID::class.java) }.singleOrNull()

    fun redeem(assertion: String): UUID? = jdbc.query(
        """
        delete from mcp_oauth_assertions a using mcp_oauth_access t, users u
        where a.assertion_hash = :assertion and a.expires_at > statement_timestamp()
          and t.token_hash = a.access_hash and u.id = t.user_id and $CURRENT_ACCESS
        returning t.user_id
        """.trimIndent(),
        registration + mapOf("assertion" to digest(assertion)),
    ) { row, _ -> row.getObject("user_id", UUID::class.java) }.singleOrNull()

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    fun pruneExpired() {
        jdbc.jdbcOperations.update("delete from mcp_oauth_assertions where expires_at <= statement_timestamp()")
        jdbc.jdbcOperations.update("delete from mcp_oauth_access where expires_at <= statement_timestamp()")
        jdbc.jdbcOperations.update("delete from mcp_oauth_flows where expires_at <= statement_timestamp()")
    }

    private fun browser(flow: String, csrf: String) = mapOf("flow" to digest(flow), "csrf" to digest(csrf))

    private companion object {
        const val PENDING_FLOW = """
            flow_hash = :flow and csrf_hash = :csrf and client_id = :clientId and redirect_uri = :redirectUri
            and resource = :resource and scope = :scope and code_hash is null and expires_at > statement_timestamp()
        """
        const val CURRENT_ACCESS = """
            t.client_id = :clientId and t.resource = :resource and t.scope = :scope
            and t.expires_at > statement_timestamp() and t.credentials_version = u.credentials_version
            and u.role = 'AGENT' and u.status = 'ACTIVE'
        """
    }
}
