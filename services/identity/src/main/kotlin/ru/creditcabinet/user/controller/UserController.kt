package ru.creditcabinet.user.controller

import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Size
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.auth.application.AuthService
import ru.creditcabinet.auth.controller.sessionToken
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.user.application.UserQueries
import ru.creditcabinet.user.domain.UserRole
import ru.creditcabinet.user.dto.UserResponse
import java.util.UUID

@Tag(name = "Текущий пользователь")
@RestController
@RequestMapping("/api/v1/users")
class UserController(private val authService: AuthService, private val users: UserQueries) {
    @Operation(summary = "Профиль текущего пользователя и его роль")
    @GetMapping("/me")
    fun me(@RequestHeader("Authorization", required = false) authorization: String?): UserResponse =
        UserResponse.of(authService.currentUser(sessionToken(authorization)))

    @Operation(summary = "Сменить свой аватар: готовая картинка, своё фото до 64 КБ или инициалы")
    @PutMapping("/me/avatar")
    fun changeAvatar(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @RequestBody request: AvatarRequest,
    ): UserResponse = UserResponse.of(users.changeAvatar(authService.currentUser(sessionToken(authorization)), request.avatar))

    data class AvatarRequest(
        /** `preset:<code>`, `data:image/(png|jpeg|webp);base64,...` or null. */
        @field:Size(max = 90_000) val avatar: String? = null,
    )
}

/**
 * User lookups for other services (names of employees in an application's history, recipients
 * of notifications) and the token check. Not routed by the gateway.
 */
@Hidden
@RestController
@RequestMapping("/internal/v1/users")
class InternalUserController(private val users: UserQueries) {
    @GetMapping
    fun list(
        @RequestParam(required = false) ids: List<UUID>?,
        @RequestParam(required = false) companyId: UUID?,
        @RequestParam(required = false) role: UserRole?,
    ): List<UserResponse> = users.list(ids, companyId, role).map(UserResponse::of)

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): UserResponse = UserResponse.of(users.get(id))

    /** For the token check of other services: a token older than this version is revoked. */
    @GetMapping("/{id}/credentials")
    fun credentials(@PathVariable id: UUID): CredentialsResponse =
        users.activeCredentialsVersion(id)?.let(::CredentialsResponse)
            ?: throw NotFoundException("Пользователь не найден")

    data class CredentialsResponse(val credentialsVersion: Int)
}
