package ru.creditcabinet.access.dto

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import ru.creditcabinet.user.dto.UserResponse
import java.time.Instant
import java.util.UUID

data class RecoveryRequest(
    @field:NotBlank(message = "Укажите email")
    @field:Email(message = "Email должен быть корректным")
    @field:Size(max = 255, message = "Email должен быть не длиннее 255 символов") val email: String,
)
data class ResetPasswordRequest(
    @field:NotBlank(message = "Откройте одноразовую ссылку")
    @field:Size(max = 100, message = "Ссылка недействительна") val token: String,
    @field:NotBlank(message = "Укажите новый пароль") val password: String,
)
data class ChangePasswordRequest(
    @field:NotBlank(message = "Укажите текущий пароль") val currentPassword: String,
    @field:NotBlank(message = "Укажите новый пароль") val password: String,
)
data class InviteEmployeeRequest(
    @field:NotBlank(message = "Укажите email сотрудника")
    @field:Email(message = "Email должен быть корректным")
    @field:Size(max = 255, message = "Email должен быть не длиннее 255 символов") val email: String,
    val canSign: Boolean = false,
)
data class AcceptInvitationRequest(
    @field:NotBlank(message = "Откройте одноразовую ссылку")
    @field:Size(max = 100, message = "Ссылка недействительна") val token: String,
    @field:NotBlank(message = "Укажите имя")
    @field:Size(max = 255, message = "Имя должно быть не длиннее 255 символов") val fullName: String,
    @field:NotBlank(message = "Укажите пароль") val password: String,
    @field:AssertTrue(message = "Без согласия на обработку персональных данных принять приглашение нельзя") val personalDataConsent: Boolean = false,
)
data class InspectInvitationRequest(
    @field:NotBlank(message = "Откройте одноразовую ссылку")
    @field:Size(max = 100, message = "Ссылка недействительна") val token: String,
)
data class AccessMessage(val message: String)
data class InvitationResponse(val id: UUID, val email: String, val canSign: Boolean, val expiresAt: Instant, val status: String, val link: String? = null)
data class EmployeeListResponse(val members: List<UserResponse>, val invitations: List<InvitationResponse>, val canManage: Boolean, val mailAvailable: Boolean)
data class InvitationPreview(val companyName: String, val email: String, val canSign: Boolean, val expiresAt: Instant)
