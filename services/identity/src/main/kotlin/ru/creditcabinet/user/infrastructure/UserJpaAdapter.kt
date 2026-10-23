package ru.creditcabinet.user.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import ru.creditcabinet.user.domain.User
import ru.creditcabinet.user.domain.UserDataPort
import ru.creditcabinet.user.domain.UserRole
import ru.creditcabinet.user.domain.UserStatus
import java.time.Instant
import java.util.UUID

interface UserJpaRepository : JpaRepository<UserEntity, UUID> {
    fun findByEmail(email: String): UserEntity?
    fun findByCompanyIdOrderByCreatedAt(companyId: UUID): List<UserEntity>
    fun findByRoleAndStatus(role: UserRole, status: UserStatus): List<UserEntity>
}

@Component
class UserJpaAdapter(
    private val repository: UserJpaRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jdbc: JdbcTemplate,
) : UserDataPort {
    override fun findById(id: UUID): User? = repository.findById(id).orElse(null)?.toDomain()

    override fun findByEmail(email: String): User? = repository.findByEmail(email.lowercase())?.toDomain()

    override fun findByIds(ids: Collection<UUID>): List<User> =
        if (ids.isEmpty()) emptyList() else repository.findAllById(ids).map(UserEntity::toDomain)

    override fun findByCompany(companyId: UUID): List<User> = repository.findByCompanyIdOrderByCreatedAt(companyId).map(UserEntity::toDomain)

    // Blocked staff get nothing: they cannot act on it.
    override fun findByRole(role: UserRole): List<User> = repository.findByRoleAndStatus(role, UserStatus.ACTIVE).map(UserEntity::toDomain)

    override fun verifyPassword(email: String, password: String): Boolean = authenticate(email, password) != null

    override fun authenticate(email: String, password: String): User? {
        val entity = repository.findByEmail(email.lowercase())
        val snapshot = entity?.toDomain()
        // Hash anyway for an unknown address, so both answers take the same time.
        return snapshot.takeIf { passwordEncoder.matches(password, entity?.passwordHash ?: absentUserHash) }
    }

    override fun recordSignIn(userId: UUID, credentialsVersion: Int, at: Instant): Boolean =
        jdbc.update(
            "update users set last_sign_in_at=? where id=? and credentials_version=? and status='ACTIVE'",
            java.sql.Timestamp.from(at),
            userId,
            credentialsVersion,
        ) == 1

    private val absentUserHash: String by lazy { passwordEncoder.encode(UUID.randomUUID().toString()) }

    override fun save(user: User, password: String?): User {
        val passwordHash = password?.let(passwordEncoder::encode)
            ?: repository.findById(user.id).orElse(null)?.passwordHash
            ?: error("A new user cannot be saved without a password")
        repository.save(UserEntity.from(user, passwordHash))
        return user
    }
}
