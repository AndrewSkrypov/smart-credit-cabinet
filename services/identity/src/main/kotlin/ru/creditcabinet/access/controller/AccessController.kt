package ru.creditcabinet.access.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.access.application.CompanyAccessService
import ru.creditcabinet.access.application.PasswordAccessService
import ru.creditcabinet.access.dto.AcceptInvitationRequest
import ru.creditcabinet.access.dto.AccessMessage
import ru.creditcabinet.access.dto.ChangePasswordRequest
import ru.creditcabinet.access.dto.EmployeeListResponse
import ru.creditcabinet.access.dto.InspectInvitationRequest
import ru.creditcabinet.access.dto.InvitationPreview
import ru.creditcabinet.access.dto.InvitationResponse
import ru.creditcabinet.access.dto.InviteEmployeeRequest
import ru.creditcabinet.access.dto.RecoveryRequest
import ru.creditcabinet.access.dto.ResetPasswordRequest
import ru.creditcabinet.auth.dto.UserSessionResponse
import ru.creditcabinet.common.security.JwtVerifier
import java.util.UUID

@Tag(name = "Пароли и сотрудники компании")
@RestController
@RequestMapping("/api/v1")
class AccessController(
    private val passwords: PasswordAccessService,
    private val employees: CompanyAccessService,
    private val verifier: JwtVerifier,
) {
    @Operation(summary = "Запросить одноразовую ссылку восстановления; ответ не раскрывает наличие адреса")
    @PostMapping("/auth/password-recovery")
    fun recover(@Valid @RequestBody body: RecoveryRequest, request: HttpServletRequest): AccessMessage =
        passwords.requestRecovery(body.email, request.getHeader("X-Real-IP")?.take(128) ?: request.remoteAddr)

    @Operation(summary = "Установить пароль по одноразовой ссылке за 30 минут и отозвать все сессии")
    @PostMapping("/auth/password-reset")
    fun reset(@Valid @RequestBody body: ResetPasswordRequest): AccessMessage = passwords.resetPassword(body)

    @Operation(summary = "Сменить пароль с проверкой текущего и отозвать все сессии")
    @PostMapping("/auth/password")
    fun change(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @Valid @RequestBody body: ChangePasswordRequest,
    ): AccessMessage = passwords.changePassword(verifier.requireClaims(authorization), body)

    @Operation(summary = "Выйти на всех устройствах и отменить ожидающие ссылки восстановления")
    @PostMapping("/auth/logout-all")
    fun logoutAll(@RequestHeader("Authorization", required = false) authorization: String?): AccessMessage =
        passwords.logoutAll(verifier.requireClaims(authorization))

    @Operation(summary = "Получить сотрудников и приглашения своей компании")
    @GetMapping("/companies/me/employees")
    fun employees(@RequestHeader("Authorization", required = false) authorization: String?): EmployeeListResponse =
        employees.list(verifier.requireClaims(authorization))

    @Operation(summary = "Пригласить сотрудника на 72 часа; нужно право подписи; без почты ссылка возвращается один раз")
    @PostMapping("/companies/me/invitations")
    fun invite(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @RequestHeader("Idempotency-Key") key: UUID,
        @Valid @RequestBody body: InviteEmployeeRequest,
    ): InvitationResponse = employees.invite(verifier.requireClaims(authorization), key, body)

    @Operation(summary = "Заменить приглашение новым и отменить прежнюю ссылку; нужно право подписи")
    @PostMapping("/companies/me/invitations/{id}/resend")
    fun resend(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key") key: UUID,
    ): InvitationResponse = employees.resend(verifier.requireClaims(authorization), id, key)

    @Operation(summary = "Отменить ещё не принятое приглашение; нужно право подписи")
    @DeleteMapping("/companies/me/invitations/{id}")
    fun cancel(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key") key: UUID,
    ): AccessMessage = employees.cancelInvitation(verifier.requireClaims(authorization), id, key)

    @Operation(summary = "Отозвать доступ сотрудника и все его сессии; последнего подписанта отозвать нельзя")
    @DeleteMapping("/companies/me/employees/{id}/access")
    fun revoke(
        @RequestHeader("Authorization", required = false) authorization: String?,
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key") key: UUID,
    ): AccessMessage = employees.revoke(verifier.requireClaims(authorization), id, key)

    @Operation(summary = "Проверить одноразовую ссылку приглашения без входа")
    @PostMapping("/auth/invitations/inspect")
    fun inspect(@Valid @RequestBody body: InspectInvitationRequest): InvitationPreview = employees.preview(body.token)

    @Operation(summary = "Принять одноразовое приглашение с паролем и согласием на обработку данных")
    @PostMapping("/auth/invitations/accept")
    fun accept(@Valid @RequestBody body: AcceptInvitationRequest): UserSessionResponse = employees.accept(body)
}
