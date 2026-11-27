package ru.creditcabinet.user.application

import org.springframework.stereotype.Service
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.user.domain.User
import ru.creditcabinet.user.domain.UserDataPort
import ru.creditcabinet.user.domain.UserRole
import ru.creditcabinet.user.domain.UserStatus
import java.util.UUID

/** Application-layer queries for internal user lookups and credential version checks. */
@Service
class UserService(private val users: UserDataPort) {
    fun list(ids: List<UUID>?, companyId: UUID?, role: UserRole?): List<User> = when {
        companyId != null -> users.findByCompany(companyId).filter { it.status == UserStatus.ACTIVE }
        role != null -> users.findByRole(role)
        else -> users.findByIds(ids.orEmpty().distinct().take(MAX_IDS))
    }

    fun get(id: UUID): User = users.findById(id) ?: throw NotFoundException("Пользователь не найден")

    fun activeCredentialsVersion(id: UUID): Int =
        users.findById(id)?.takeIf { it.status == UserStatus.ACTIVE }?.credentialsVersion
            ?: throw NotFoundException("Пользователь не найден")

    private companion object {
        const val MAX_IDS = 500
    }
}
