package ru.creditcabinet.user.domain

import java.time.Instant
import java.util.UUID

enum class UserRole {
    /** A person acting for a company: owner, director, accountant or another employee. */
    CLIENT,

    /** Bank: checks completeness, requests missing documents. */
    MANAGER,

    /** Bank: decides on applications sent to manual review. */
    UNDERWRITER,

    /** Bank: products, rule thresholds, funnel. */
    RISK_ADMIN,
}

enum class UserStatus { ACTIVE, BLOCKED }

data class User(
    val id: UUID = UUID.randomUUID(),
    val fullName: String,
    val email: String,
    val role: UserRole,
    val status: UserStatus = UserStatus.ACTIVE,
    /** The company a client acts for; bank staff have none. */
    val companyId: UUID? = null,
    /** May sign contracts for the company (owner or director). */
    val canSign: Boolean = false,
    val credentialsVersion: Int = 0,
    val createdAt: Instant = Instant.now(),
    val lastSignInAt: Instant? = null,
) {
    init {
        require(fullName.isNotBlank()) { "Укажите имя" }
        require(email.contains("@")) { "Email должен быть корректным" }
        require(role == UserRole.CLIENT || companyId == null) { "Сотрудник банка не может представлять компанию" }
        require(!canSign || companyId != null) { "Право подписи бывает только у представителя компании" }
    }

    /** Every token issued before this moment stops working. */
    fun revokeTokens(): User = copy(credentialsVersion = credentialsVersion + 1)
}
