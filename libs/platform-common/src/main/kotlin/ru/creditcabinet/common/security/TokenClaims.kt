package ru.creditcabinet.common.security

import java.util.UUID

/**
 * What a cabinet token says about its user. The identity service writes these claims when it
 * signs a token; other services read them instead of looking the user up on every call.
 */
data class TokenClaims(
    val userId: UUID,
    val role: String?,
    val fullName: String?,
    val email: String?,
    /** The company a client acts for; null for bank staff. Every client-side check is scoped by it. */
    val companyId: UUID? = null,
    /** May sign contracts on behalf of [companyId] (owner or director). */
    val canSign: Boolean = false,
    /** Bumped by identity on a password change or block; a token with an older value is revoked. */
    val credentialsVersion: Int = 0,
) {
    val isClient: Boolean get() = role == Roles.CLIENT
    val isBankStaff: Boolean get() = role in Roles.BANK_STAFF

    companion object {
        const val ROLE = "role"
        const val FULL_NAME = "name"
        const val EMAIL = "email"
        const val COMPANY_ID = "cid"
        const val CAN_SIGN = "sign"
        const val CREDENTIALS_VERSION = "cv"
    }
}

/** Role names as they travel in tokens; identity's UserRole enum uses the same names. */
object Roles {
    const val CLIENT = "CLIENT"
    const val MANAGER = "MANAGER"
    const val UNDERWRITER = "UNDERWRITER"
    const val RISK_ADMIN = "RISK_ADMIN"

    val BANK_STAFF = setOf(MANAGER, UNDERWRITER, RISK_ADMIN)
}
