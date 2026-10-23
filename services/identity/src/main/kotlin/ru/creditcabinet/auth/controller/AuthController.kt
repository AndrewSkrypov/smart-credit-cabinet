package ru.creditcabinet.auth.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.auth.application.AuthService
import ru.creditcabinet.auth.dto.AuthOptionsResponse
import ru.creditcabinet.auth.dto.SignInRequest
import ru.creditcabinet.auth.dto.SignUpRequest
import ru.creditcabinet.auth.dto.UserSessionResponse
import ru.creditcabinet.common.mail.MailCapabilities

@Tag(name = "Вход и регистрация")
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    private val mail: MailCapabilities,
    @Value("\${spring.flyway.placeholders.demo-accounts:true}") private val demoAccounts: Boolean,
) {
    @Operation(summary = "Настройки входа: доступность демо-учётных записей")
    @GetMapping("/options")
    fun options(): AuthOptionsResponse = AuthOptionsResponse(demoAccounts = demoAccounts, mailAvailable = mail.enabled())

    @Operation(summary = "Подключить компанию по ИНН с согласием на обработку данных")
    @PostMapping("/sign-up")
    fun signUp(@Valid @RequestBody request: SignUpRequest): UserSessionResponse = authService.signUp(request)

    @Operation(summary = "Войти по email и паролю, получить подписанный JWT")
    @PostMapping("/sign-in")
    fun signIn(@Valid @RequestBody request: SignInRequest, servlet: HttpServletRequest): UserSessionResponse =
        authService.signIn(request, servlet.getHeader("X-Real-IP")?.take(128) ?: servlet.remoteAddr)

    /** A fresh token for the current user, e.g. after their company or rights changed. */
    @Operation(summary = "Проверить текущего пользователя и обновить JWT с актуальными правами")
    @GetMapping("/session")
    fun session(@RequestHeader("Authorization", required = false) authorization: String?): UserSessionResponse =
        authService.session(authorization)
}
