package ru.creditcabinet.auth.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import ru.creditcabinet.auth.domain.JuryLoginGrantClaim
import ru.creditcabinet.auth.domain.JuryLoginGrantPort
import java.util.UUID

@Component
class JuryLoginGrantStore(private val jdbc: JdbcTemplate) : JuryLoginGrantPort {
    override fun consume(tokenHash: String): JuryLoginGrantClaim? {
        // Lock the account first, in the same order as provisioning and revoking a grant.
        val eligible = jdbc.query(
            """
            select account.id from users account
            join jury_login_grants ticket on ticket.user_id = account.id
            where ticket.token_hash = ? and account.jury_link_enabled and account.status = 'ACTIVE'
                and account.credentials_version = ticket.credentials_version
                and ticket.used_at is null and ticket.revoked_at is null and ticket.expires_at > statement_timestamp()
            for update of account
            """.trimIndent(),
            { row, _ -> row.getObject("id", UUID::class.java) },
            tokenHash,
        ).singleOrNull() ?: return null

        return jdbc.query(
            """
            update jury_login_grants set used_at = statement_timestamp()
            where token_hash = ? and user_id = ? and used_at is null and revoked_at is null
                and expires_at > statement_timestamp()
            returning user_id, credentials_version, used_at
            """.trimIndent(),
            { row, _ ->
                JuryLoginGrantClaim(
                    userId = row.getObject("user_id", UUID::class.java),
                    credentialsVersion = row.getInt("credentials_version"),
                    usedAt = row.getTimestamp("used_at").toInstant(),
                )
            },
            tokenHash,
            eligible,
        ).singleOrNull()
    }
}
