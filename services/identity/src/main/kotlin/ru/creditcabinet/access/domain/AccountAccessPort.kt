package ru.creditcabinet.access.domain

import ru.creditcabinet.user.domain.User
import java.time.Instant
import java.util.UUID

data class StoredAccount(val user: User, val passwordHash: String)
data class StoredReset(val grant: AccessGrant, val userId: UUID, val credentialsVersion: Int, val keyVersion: String)
data class StoredInvitation(
    val grant: AccessGrant,
    val companyId: UUID,
    val email: String,
    val canSign: Boolean,
    val invitedBy: UUID,
    val keyVersion: String,
)

interface AccountAccessPort {
    fun account(id: UUID, lock: Boolean = false): StoredAccount?
    fun accountByEmail(email: String): StoredAccount?
    fun lockCompany(id: UUID)
    fun companyName(id: UUID): String?
    fun reset(id: UUID, lock: Boolean = false): StoredReset?
    fun invitation(id: UUID, lock: Boolean = false): StoredInvitation?
    fun invitations(companyId: UUID): List<StoredInvitation>
    fun members(companyId: UUID): List<User>
    fun insertReset(reset: StoredReset, createdAt: Instant)
    fun useReset(id: UUID, at: Instant)
    fun revokeResets(userId: UUID, at: Instant)
    fun changePassword(userId: UUID, hash: String)
    fun updateProfile(userId: UUID, fullName: String, email: String)
    fun revokeSessions(userId: UUID)
    fun expireInvitations(companyId: UUID, email: String, at: Instant)
    fun insertInvitation(invitation: StoredInvitation, createdAt: Instant)
    fun cancelInvitation(id: UUID, at: Instant)
    fun useInvitation(id: UUID, userId: UUID, at: Instant)
    fun revokeEmployee(id: UUID)
    fun cancelInvitationsByActor(id: UUID, at: Instant)
    fun saveInvitee(user: User, passwordHash: String)
    fun audit(actor: UUID?, action: String, subject: UUID?, company: UUID?, at: Instant)
    fun replay(actor: UUID, key: UUID, operation: String, payload: String): UUID?
    fun remember(actor: UUID, key: UUID, operation: String, payload: String, result: UUID)
}

interface AccountLinkIssuer {
    val keyVersion: String
    fun hash(purpose: LinkPurpose, id: UUID): String
    fun link(purpose: LinkPurpose, id: UUID): String
}

fun interface RecoveryLimits {
    fun acquire(email: String, address: String, now: Instant)
}

data class SecurityMailReference(val purpose: LinkPurpose, val referenceId: UUID)
