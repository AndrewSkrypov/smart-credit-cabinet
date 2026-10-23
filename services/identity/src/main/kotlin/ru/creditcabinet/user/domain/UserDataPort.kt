package ru.creditcabinet.user.domain

import java.time.Instant
import java.util.UUID

interface UserDataPort {
    fun findById(id: UUID): User?
    fun findByEmail(email: String): User?
    fun findByIds(ids: Collection<UUID>): List<User>
    fun findByCompany(companyId: UUID): List<User>
    fun findByRole(role: UserRole): List<User>
    fun verifyPassword(email: String, password: String): Boolean
    fun authenticate(email: String, password: String): User?
    fun recordSignIn(userId: UUID, credentialsVersion: Int, at: Instant): Boolean
    fun save(user: User, password: String? = null): User
}
