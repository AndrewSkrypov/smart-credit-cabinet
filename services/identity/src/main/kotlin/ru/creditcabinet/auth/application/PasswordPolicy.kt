package ru.creditcabinet.auth.application

import ru.creditcabinet.common.exception.BadRequestException

/** 8–72 characters with letters and digits; bcrypt reads only 72 bytes, so longer is refused. */
object PasswordPolicy {
    fun check(password: String) {
        if (password.length < 8 || password.length > 72) throw BadRequestException("Пароль должен быть от 8 до 72 символов")
        if (password.toByteArray(Charsets.UTF_8).size > 72) throw BadRequestException("Пароль слишком длинный: сократите его")
        if (password.none { it.isLetter() } || password.none { it.isDigit() }) throw BadRequestException("Пароль должен содержать буквы и цифры")
    }
}
