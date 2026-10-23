package ru.creditcabinet.user.controller

import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.auth.application.AuthService
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.user.domain.UserDataPort
import ru.creditcabinet.user.domain.UserRole
import ru.creditcabinet.user.domain.UserStatus
import ru.creditcabinet.user.dto.UserResponse
import java.util.UUID

@Tag(name = "Текущий пользователь")
@RestController
@RequestMapping("/api/v1/users")
class UserController(private val authService: AuthService) {
    @Operation(summary = "Профиль текущего пользователя и его роль")
    @GetMapping("/me")
    fun me(@RequestHeader("Authorization", required = false) authorization: String?): UserResponse =
        UserResponse.of(authService.currentUser(authorization))
}

/**
 * User lookups for other services (names of employees in an application's history, recipients
 * of notifications) and the token check. Not routed by the gateway.
 */
@Hidden
@RestController
@RequestMapping("/internal/v1/users")
class InternalUserController(private val users: UserDataPort) {
    @GetMapping
    fun list(
        @RequestParam(required = false) ids: List<UUID>?,
        @RequestParam(required = false) companyId: UUID?,
        @RequestParam(required = false) role: UserRole?,
    ): List<UserResponse> {
        val found = when {
            companyId != null -> users.findByCompany(companyId).filter { it.status == UserStatus.ACTIVE }
            role != null -> users.findByRole(role)
            else -> users.findByIds(ids.orEmpty().distinct().take(MAX_IDS))
        }
        return found.map(UserResponse::of)
    }

    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): UserResponse = users.findById(id)?.let(UserResponse::of) ?: throw NotFoundException("Пользователь не найден")

    /** For the token check of other services: a token older than this version is revoked. */
    @GetMapping("/{id}/credentials")
    fun credentials(@PathVariable id: UUID): CredentialsResponse =
        users.findById(id)?.takeIf { it.status == UserStatus.ACTIVE }?.let { CredentialsResponse(it.credentialsVersion) }
            ?: throw NotFoundException("Пользователь не найден")

    data class CredentialsResponse(val credentialsVersion: Int)

    private companion object {
        const val MAX_IDS = 500
    }
}
