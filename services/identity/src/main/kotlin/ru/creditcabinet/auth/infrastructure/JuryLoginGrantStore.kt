package ru.creditcabinet.auth.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import ru.creditcabinet.auth.domain.JuryLoginAccount
import ru.creditcabinet.auth.domain.JuryLoginGrantPort
import ru.creditcabinet.user.domain.UserRole
import java.util.UUID

@Component
class JuryLoginGrantStore(private val jdbc: JdbcTemplate) : JuryLoginGrantPort {
    override fun inspect(tokenHash: String): JuryLoginAccount? = jdbc.query(
        "select account.full_name, account.email, account.role $ELIGIBLE_ACCOUNT",
        { row, _ ->
            JuryLoginAccount(
                fullName = row.getString("full_name"),
                email = row.getString("email"),
                role = UserRole.valueOf(row.getString("role")),
            )
        },
        tokenHash,
    ).singleOrNull()

    override fun consume(tokenHash: String): UUID? {
        // Lock the account before its grant, in the same order as provisioning.
        val userId = jdbc.query(
            "select account.id $ELIGIBLE_ACCOUNT for update of account",
            { row, _ -> row.getObject("id", UUID::class.java) },
            tokenHash,
        ).singleOrNull() ?: return null

        // The fresh statement observes any redemption or revocation while waiting for the account lock.
        return jdbc.query(
            """
            with consumed as (
                update jury_login_grants set used_at = statement_timestamp()
                where token_hash = ? and user_id = ? and used_at is null and revoked_at is null
                    and expires_at > statement_timestamp()
                returning user_id, used_at
            )
            update users account set last_sign_in_at = consumed.used_at
            from consumed where account.id = consumed.user_id
            returning account.id
            """.trimIndent(),
            { row, _ -> row.getObject("id", UUID::class.java) },
            tokenHash,
            userId,
        ).singleOrNull()
    }

    companion object {
        private val ELIGIBLE_ACCOUNT = """
            from users account join jury_login_grants ticket on ticket.user_id = account.id
            where ticket.token_hash = ? and account.jury_link_enabled and account.status = 'ACTIVE'
                and account.credentials_version = ticket.credentials_version
                and ticket.used_at is null and ticket.revoked_at is null and ticket.expires_at > statement_timestamp()
        """.trimIndent()
    }
}
