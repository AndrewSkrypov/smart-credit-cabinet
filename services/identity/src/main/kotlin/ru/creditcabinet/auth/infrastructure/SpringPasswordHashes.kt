package ru.creditcabinet.auth.infrastructure

import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Component
import ru.creditcabinet.auth.application.PasswordHashes

@Component
class SpringPasswordHashes(private val encoder: PasswordEncoder) : PasswordHashes {
    override fun encode(password: String): String = encoder.encode(password)

    override fun matches(password: String, hash: String): Boolean = encoder.matches(password, hash)
}
