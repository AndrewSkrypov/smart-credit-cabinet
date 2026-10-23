package ru.creditcabinet.access.infrastructure

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import ru.creditcabinet.access.domain.AccessGrant
import ru.creditcabinet.access.domain.AccountAccessPort
import ru.creditcabinet.access.domain.StoredAccount
import ru.creditcabinet.access.domain.StoredInvitation
import ru.creditcabinet.access.domain.StoredReset
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.observability.RequestIds
import ru.creditcabinet.common.security.RevocationWatermarks
import ru.creditcabinet.user.domain.User
import ru.creditcabinet.user.domain.UserRole
import ru.creditcabinet.user.domain.UserStatus
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

@Component
class AccessStore(private val jdbc: NamedParameterJdbcTemplate, private val revocations: RevocationWatermarks) : AccountAccessPort {
    override fun account(id: UUID, lock: Boolean): StoredAccount? = jdbc.query(
        "select * from users where id=:id" + if (lock) " for update" else "",
        mapOf("id" to id),
    ) { rs, _ -> account(rs) }.firstOrNull()

    override fun accountByEmail(email: String): StoredAccount? = jdbc.query(
        "select * from users where email=:email",
        mapOf("email" to email),
    ) { rs, _ -> account(rs) }.firstOrNull()

    override fun lockCompany(id: UUID) {
        if (jdbc.queryForList("select id from companies where id=:id for update", mapOf("id" to id)).isEmpty()) throw NotFoundException("Компания не найдена")
    }

    override fun companyName(id: UUID): String? = jdbc.query(
        "select coalesce(name,'Компания') as name from companies where id=:id",
        mapOf("id" to id),
    ) { rs, _ -> rs.getString("name") }.firstOrNull()

    override fun reset(id: UUID, lock: Boolean): StoredReset? = jdbc.query(
        "select * from password_reset_requests where id=:id" + if (lock) " for update" else "",
        mapOf("id" to id),
    ) { rs, _ ->
        StoredReset(grant(rs), rs.getObject("user_id", UUID::class.java), rs.getInt("credentials_version"), rs.getString("key_version"))
    }.firstOrNull()

    override fun invitation(id: UUID, lock: Boolean): StoredInvitation? = jdbc.query(
        "select * from company_invitations where id=:id" + if (lock) " for update" else "",
        mapOf("id" to id),
    ) { rs, _ -> invitation(rs) }.firstOrNull()

    override fun invitations(companyId: UUID): List<StoredInvitation> = jdbc.query(
        "select * from company_invitations where company_id=:company order by created_at desc, id desc",
        mapOf("company" to companyId),
    ) { rs, _ -> invitation(rs) }

    override fun members(companyId: UUID): List<User> = jdbc.query(
        "select * from users where company_id=:company order by created_at, id",
        mapOf("company" to companyId),
    ) { rs, _ -> account(rs).user }

    override fun insertReset(reset: StoredReset, createdAt: Instant) {
        jdbc.update(
            """
            insert into password_reset_requests (id,user_id,token_hash,key_version,credentials_version,created_at,expires_at)
            values (:id,:user,:hash,:key,:version,:at,:expires)
            """.trimIndent(),
            mapOf(
                "id" to reset.grant.id,
                "user" to reset.userId,
                "hash" to reset.grant.tokenHash,
                "key" to reset.keyVersion,
                "version" to reset.credentialsVersion,
                "at" to Timestamp.from(createdAt),
                "expires" to Timestamp.from(reset.grant.expiresAt),
            ),
        )
    }

    override fun useReset(id: UUID, at: Instant) {
        jdbc.update("update password_reset_requests set used_at=:at where id=:id", mapOf("id" to id, "at" to Timestamp.from(at)))
    }

    override fun changePassword(userId: UUID, hash: String) {
        jdbc.update("update users set password_hash=:hash,credentials_version=credentials_version+1 where id=:id", mapOf("id" to userId, "hash" to hash))
        publishVersion(userId)
    }

    override fun revokeSessions(userId: UUID) {
        jdbc.update("update users set credentials_version=credentials_version+1 where id=:id", mapOf("id" to userId))
        publishVersion(userId)
    }

    override fun revokeResets(userId: UUID, at: Instant) {
        jdbc.update(
            "update password_reset_requests set revoked_at=:at where user_id=:user and used_at is null and revoked_at is null",
            mapOf("user" to userId, "at" to Timestamp.from(at)),
        )
    }

    override fun expireInvitations(companyId: UUID, email: String, at: Instant) {
        jdbc.update(
            """
            update company_invitations set revoked_at=:at
            where company_id=:company and email=:email and used_at is null and revoked_at is null and expires_at<=:at
            """.trimIndent(),
            mapOf("company" to companyId, "email" to email, "at" to Timestamp.from(at)),
        )
    }

    override fun insertInvitation(invitation: StoredInvitation, createdAt: Instant) {
        jdbc.update(
            """
            insert into company_invitations (id,company_id,email,can_sign,invited_by,token_hash,key_version,created_at,expires_at)
            values (:id,:company,:email,:sign,:actor,:hash,:key,:at,:expires)
            """.trimIndent(),
            mapOf(
                "id" to invitation.grant.id, "company" to invitation.companyId, "email" to invitation.email, "sign" to invitation.canSign,
                "actor" to invitation.invitedBy, "hash" to invitation.grant.tokenHash, "key" to invitation.keyVersion,
                "at" to Timestamp.from(createdAt), "expires" to Timestamp.from(invitation.grant.expiresAt),
            ),
        )
    }

    override fun cancelInvitation(id: UUID, at: Instant) {
        jdbc.update("update company_invitations set revoked_at=:at where id=:id", mapOf("id" to id, "at" to Timestamp.from(at)))
    }

    override fun useInvitation(id: UUID, userId: UUID, at: Instant) {
        jdbc.update(
            "update company_invitations set used_at=:at,accepted_by=:user where id=:id",
            mapOf("id" to id, "user" to userId, "at" to Timestamp.from(at)),
        )
    }

    override fun revokeEmployee(id: UUID) {
        jdbc.update("update users set status='BLOCKED',credentials_version=credentials_version+1 where id=:id", mapOf("id" to id))
        publishVersion(id)
    }

    override fun cancelInvitationsByActor(id: UUID, at: Instant) {
        jdbc.update(
            "update company_invitations set revoked_at=:at where invited_by=:id and used_at is null and revoked_at is null",
            mapOf("id" to id, "at" to Timestamp.from(at)),
        )
    }

    override fun saveInvitee(user: User, passwordHash: String) {
        try {
            jdbc.update(
                """
                insert into users (id,full_name,email,role,status,company_id,can_sign,password_hash,credentials_version)
                values (:id,:name,:email,'CLIENT','ACTIVE',:company,:sign,:hash,:version)
                on conflict (id) do update set full_name=:name,status='ACTIVE',can_sign=:sign,password_hash=:hash,credentials_version=:version
                """.trimIndent(),
                mapOf(
                    "id" to user.id,
                    "name" to user.fullName,
                    "email" to user.email,
                    "company" to user.companyId,
                    "sign" to user.canSign,
                    "hash" to passwordHash,
                    "version" to user.credentialsVersion,
                ),
            )
        } catch (_: DataIntegrityViolationException) {
            throw ConflictException("Этот адрес уже используется. Попросите приглашающего указать другой.")
        }
        publishVersion(user.id)
    }

    private fun publishVersion(id: UUID) {
        val version = jdbc.queryForObject("select credentials_version from users where id=:id", mapOf("id" to id), Int::class.java)!!
        revocations.afterCommit(id, version)
    }

    override fun audit(actor: UUID?, action: String, subject: UUID?, company: UUID?, at: Instant) {
        jdbc.update(
            "insert into identity_audit (id, actor_id, action, subject_id, company_id, occurred_at, request_id) values (:id,:actor,:action,:subject,:company,:at,:request)",
            mapOf(
                "id" to UUID.randomUUID(),
                "actor" to actor,
                "action" to action,
                "subject" to subject,
                "company" to company,
                "at" to Timestamp.from(at),
                "request" to RequestIds.current()?.take(64),
            ),
        )
    }

    override fun replay(actor: UUID, key: UUID, operation: String, payload: String): UUID? = jdbc.query(
        "select operation,payload_hash,result_id from identity_mutation_keys where actor_id=:actor and mutation_key=:key",
        mapOf("actor" to actor, "key" to key),
    ) { rs, _ ->
        if (rs.getString("operation") != operation || rs.getString("payload_hash") != AccessGrant.hash(payload)) {
            throw ConflictException("Этот ключ повтора уже использован для другого действия")
        }
        rs.getObject("result_id", UUID::class.java)
    }.firstOrNull()

    override fun remember(actor: UUID, key: UUID, operation: String, payload: String, result: UUID) {
        jdbc.update(
            "insert into identity_mutation_keys (actor_id,mutation_key,operation,payload_hash,result_id) values (:actor,:key,:operation,:hash,:result)",
            mapOf("actor" to actor, "key" to key, "operation" to operation, "hash" to AccessGrant.hash(payload), "result" to result),
        )
    }

    private fun account(rs: ResultSet) = StoredAccount(
        User(
            id = rs.getObject("id", UUID::class.java), fullName = rs.getString("full_name"), email = rs.getString("email"),
            role = UserRole.valueOf(rs.getString("role")), status = UserStatus.valueOf(rs.getString("status")),
            companyId = rs.getObject("company_id", UUID::class.java), canSign = rs.getBoolean("can_sign"),
            credentialsVersion = rs.getInt("credentials_version"), createdAt = rs.getTimestamp("created_at").toInstant(),
            lastSignInAt = rs.getTimestamp("last_sign_in_at")?.toInstant(),
        ),
        rs.getString("password_hash"),
    )

    private fun grant(rs: ResultSet) = AccessGrant(
        rs.getObject("id", UUID::class.java),
        rs.getString("token_hash"),
        rs.getTimestamp("expires_at").toInstant(),
        rs.getTimestamp("used_at")?.toInstant(),
        rs.getTimestamp("revoked_at")?.toInstant(),
    )

    private fun invitation(rs: ResultSet) = StoredInvitation(
        grant(rs),
        rs.getObject("company_id", UUID::class.java),
        rs.getString("email"),
        rs.getBoolean("can_sign"),
        rs.getObject("invited_by", UUID::class.java),
        rs.getString("key_version"),
    )
}
