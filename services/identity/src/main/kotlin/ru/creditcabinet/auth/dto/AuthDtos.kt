package ru.creditcabinet.auth.dto

import jakarta.validation.constraints.AssertTrue
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import ru.creditcabinet.user.dto.UserResponse

data class SignInRequest(
    @field:NotBlank(message = "Укажите email")
    val email: String,
    @field:NotBlank(message = "Укажите пароль")
    val password: String,
)

/** Registration of a business: the person and the company they represent. */
data class SignUpRequest(
    @field:NotBlank(message = "Укажите имя")
    @field:Size(max = 255)
    val fullName: String,
    @field:NotBlank(message = "Укажите email")
    @field:Email(message = "Email должен быть корректным")
    val email: String,
    @field:NotBlank(message = "Укажите пароль")
    val password: String,
    @field:NotBlank(message = "Укажите ИНН компании или ИП")
    val inn: String,
    @field:AssertTrue(message = "Без согласия на обработку персональных данных зарегистрироваться нельзя")
    val personalDataConsent: Boolean = false,
)

data class UserSessionResponse(val token: String, val user: UserResponse)

/** What the sign-in page may offer: one-click demo accounts exist only where they are enabled. */
data class AuthOptionsResponse(val demoAccounts: Boolean, val mailAvailable: Boolean = false)
