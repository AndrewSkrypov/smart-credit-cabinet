package ru.creditcabinet.user.infrastructure

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import ru.creditcabinet.user.domain.User
import ru.creditcabinet.user.domain.UserDataPort
import java.util.UUID

interface UserJpaRepository : JpaRepository<UserEntity, UUID> {
    fun findByEmail(email: String): UserEntity?
    fun findByCompanyIdOrderByCreatedAt(companyId: UUID): List<UserEntity>
}

@Component
class UserJpaAdapter(
    private val repository: UserJpaRepository,
    private val passwordEncoder: PasswordEncoder,
) : UserDataPort {
    override fun findById(id: UUID): User? = repository.findById(id).orElse(null)?.toDomain()

    override fun findByEmail(email: String): User? = repository.findByEmail(email.lowercase())?.toDomain()

    override fun findByIds(ids: Collection<UUID>): List<User> =
        if (ids.isEmpty()) emptyList() else repository.findAllById(ids).map(UserEntity::toDomain)

    override fun findByCompany(companyId: UUID): List<User> = repository.findByCompanyIdOrderByCreatedAt(companyId).map(UserEntity::toDomain)

    override fun verifyPassword(email: String, password: String): Boolean {
        val entity = repository.findByEmail(email.lowercase())
        // Hash anyway for an unknown address, so both answers take the same time.
        return passwordEncoder.matches(password, entity?.passwordHash ?: absentUserHash) && entity != null
    }

    private val absentUserHash: String by lazy { passwordEncoder.encode(UUID.randomUUID().toString()) }

    override fun save(user: User, password: String?): User {
        val passwordHash = password?.let(passwordEncoder::encode)
            ?: repository.findById(user.id).orElse(null)?.passwordHash
            ?: error("A new user cannot be saved without a password")
        repository.save(UserEntity.from(user, passwordHash))
        return user
    }
}
