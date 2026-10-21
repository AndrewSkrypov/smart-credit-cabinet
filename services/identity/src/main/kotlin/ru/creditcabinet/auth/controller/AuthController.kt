package ru.creditcabinet.auth.controller

import io.swagger.v3.oas.annotations.tags.Tag
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

@Tag(name = "Auth")
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
    @Value("\${spring.flyway.placeholders.demo-accounts:true}") private val demoAccounts: Boolean,
) {
    @GetMapping("/options")
    fun options(): AuthOptionsResponse = AuthOptionsResponse(demoAccounts = demoAccounts)

    @PostMapping("/sign-up")
    fun signUp(@Valid @RequestBody request: SignUpRequest): UserSessionResponse = authService.signUp(request)

    @PostMapping("/sign-in")
    fun signIn(@Valid @RequestBody request: SignInRequest): UserSessionResponse = authService.signIn(request)

    /** A fresh token for the current user, e.g. after their company or rights changed. */
    @GetMapping("/session")
    fun session(@RequestHeader("Authorization", required = false) authorization: String?): UserSessionResponse =
        authService.session(authorization)
}
