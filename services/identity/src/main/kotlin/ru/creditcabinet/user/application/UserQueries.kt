package ru.creditcabinet.user.application

import ru.creditcabinet.identity.domain.IdentityFailure.InvalidInput
import ru.creditcabinet.identity.domain.IdentityFailure.NotFound
import ru.creditcabinet.user.domain.Avatar
import ru.creditcabinet.user.domain.User
import ru.creditcabinet.user.domain.UserDataPort
import ru.creditcabinet.user.domain.UserRole
import ru.creditcabinet.user.domain.UserStatus
import java.util.UUID

class UserQueries(private val users: UserDataPort) {
    fun list(ids: List<UUID>?, companyId: UUID?, role: UserRole?): List<User> = when {
        companyId != null -> users.findByCompany(companyId).filter { it.status == UserStatus.ACTIVE }
        role != null -> users.findByRole(role)
        else -> users.findByIds(ids.orEmpty().distinct().take(MAX_IDS))
    }

    fun get(id: UUID): User = users.findById(id) ?: throw NotFound("Пользователь не найден")

    fun activeCredentialsVersion(id: UUID): Int? =
        users.findById(id)?.takeIf { it.status == UserStatus.ACTIVE }?.credentialsVersion

    /** Replaces the caller's own picture; null returns to initials. */
    fun changeAvatar(user: User, avatar: String?): User {
        val stored = try {
            Avatar.normalize(avatar)
        } catch (invalid: IllegalArgumentException) {
            throw InvalidInput(invalid.message ?: "Аватар не подходит")
        }
        return users.save(user.copy(avatar = stored))
    }

    private companion object {
        const val MAX_IDS = 500
    }
}
