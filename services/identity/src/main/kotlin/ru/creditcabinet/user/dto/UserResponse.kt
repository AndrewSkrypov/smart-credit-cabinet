package ru.creditcabinet.user.dto

import ru.creditcabinet.user.domain.User
import ru.creditcabinet.user.domain.UserRole
import ru.creditcabinet.user.domain.UserStatus
import java.util.UUID

data class UserResponse(
    val id: UUID,
    val fullName: String,
    val email: String,
    val role: UserRole,
    val status: UserStatus,
    val companyId: UUID?,
    val canSign: Boolean,
) {
    companion object {
        fun of(user: User) = UserResponse(user.id, user.fullName, user.email, user.role, user.status, user.companyId, user.canSign)
    }
}
