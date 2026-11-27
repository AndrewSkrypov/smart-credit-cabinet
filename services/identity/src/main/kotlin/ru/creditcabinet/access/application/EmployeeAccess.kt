package ru.creditcabinet.access.application

import ru.creditcabinet.user.domain.User
import java.time.Instant
import java.util.UUID

data class EmployeeAccess(
    val members: List<User>,
    val invitations: List<InvitationDetails>,
    val canManage: Boolean,
    val mailAvailable: Boolean,
)

data class InvitationDetails(
    val id: UUID,
    val email: String,
    val canSign: Boolean,
    val expiresAt: Instant,
    val status: String,
    val link: String?,
)

data class InvitationInspection(
    val companyName: String,
    val email: String,
    val canSign: Boolean,
    val expiresAt: Instant,
)
