package ru.creditcabinet.user.domain

import java.util.UUID

interface UserDataPort {
    fun findById(id: UUID): User?
    fun findByEmail(email: String): User?
    fun findByIds(ids: Collection<UUID>): List<User>
    fun findByCompany(companyId: UUID): List<User>
    fun verifyPassword(email: String, password: String): Boolean
    fun save(user: User, password: String? = null): User
}
