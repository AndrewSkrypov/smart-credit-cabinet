package ru.creditcabinet.user.infrastructure

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import ru.creditcabinet.user.domain.User
import ru.creditcabinet.user.domain.UserRole
import ru.creditcabinet.user.domain.UserStatus
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "users")
class UserEntity(
    @Id
    val id: UUID,
    @Column(name = "full_name", nullable = false)
    val fullName: String,
    @Column(nullable = false, unique = true)
    val email: String,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val role: UserRole,
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    val status: UserStatus,
    @Column(name = "company_id")
    val companyId: UUID?,
    @Column(name = "can_sign", nullable = false)
    val canSign: Boolean,
    @Column(name = "password_hash", nullable = false)
    val passwordHash: String,
    @Column(name = "credentials_version", nullable = false)
    val credentialsVersion: Int,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant,
    @Column(name = "last_sign_in_at")
    val lastSignInAt: Instant?,
) {
    fun toDomain(): User =
        User(
            id = id,
            fullName = fullName,
            email = email,
            role = role,
            status = status,
            companyId = companyId,
            canSign = canSign,
            credentialsVersion = credentialsVersion,
            createdAt = createdAt,
            lastSignInAt = lastSignInAt,
        )

    companion object {
        fun from(user: User, passwordHash: String) =
            UserEntity(
                id = user.id,
                fullName = user.fullName,
                email = user.email,
                role = user.role,
                status = user.status,
                companyId = user.companyId,
                canSign = user.canSign,
                passwordHash = passwordHash,
                credentialsVersion = user.credentialsVersion,
                createdAt = user.createdAt,
                lastSignInAt = user.lastSignInAt,
            )
    }
}
