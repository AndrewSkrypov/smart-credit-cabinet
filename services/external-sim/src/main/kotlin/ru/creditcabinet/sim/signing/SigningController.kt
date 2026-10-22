package ru.creditcabinet.sim.signing

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.sim.control.Faults
import ru.creditcabinet.sim.dataset.Source
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class SigningRequest(
    /** The bank's id of the document being signed, e.g. the contract of an application. */
    @field:NotBlank val documentId: String,
    @field:NotBlank val documentTitle: String,
    /** The text of the document: the signature is bound to its hash. */
    @field:NotBlank val documentText: String,
    @field:NotBlank val signerName: String,
)

/**
 * A signing session. A real simple electronic signature (ПЭП) sends the one-time code by SMS;
 * the case forbids SMS in the scenario, so the code comes back here and the cabinet shows it.
 */
data class SigningSession(
    val sessionId: UUID,
    val documentId: String,
    val documentHash: String,
    val code: String,
    val expiresAt: Instant,
    val attemptsLeft: Int,
    val signatureId: UUID?,
    val signedAt: Instant?,
)

data class ConfirmRequest(@field:NotBlank val code: String)

@Tag(name = "Сервис подписания ПЭП (симулятор)")
@RestController
@RequestMapping("/ext/signing/v1")
class SigningController(private val faults: Faults) {
    private val sessions = ConcurrentHashMap<UUID, SigningSession>()
    private val random = SecureRandom()
    private val clock = Clock.systemUTC()

    @Operation(summary = "Начать подписание: выпускается одноразовый код")
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    fun start(@Valid @RequestBody request: SigningRequest): SigningSession {
        faults.enter(Source.SIGNING)
        val session = SigningSession(
            sessionId = UUID.randomUUID(),
            documentId = request.documentId,
            documentHash = sha256(request.documentText),
            code = "%06d".format(random.nextInt(1_000_000)),
            expiresAt = clock.instant().plus(TTL),
            attemptsLeft = MAX_ATTEMPTS,
            signatureId = null,
            signedAt = null,
        )
        sessions[session.sessionId] = session
        return session
    }

    @Operation(summary = "Состояние синтетической сессии подписания по её идентификатору")
    @GetMapping("/sessions/{id}")
    fun get(@PathVariable id: UUID): SigningSession = sessions[id] ?: throw NotFoundException("Сессия подписания не найдена")

    @Operation(summary = "Подтвердить подпись кодом; повтор после успеха возвращает ту же подпись")
    @PostMapping("/sessions/{id}/confirm")
    fun confirm(@PathVariable id: UUID, @Valid @RequestBody request: ConfirmRequest): SigningSession {
        faults.enter(Source.SIGNING)
        var outcome: SigningSession? = null
        var failure: RuntimeException? = null
        sessions.compute(id) { _, session ->
            when {
                session == null -> session.also { failure = NotFoundException("Сессия подписания не найдена") }
                session.signatureId != null -> session.also { outcome = it }
                session.attemptsLeft <= 0 -> session.also { failure = ConflictException("Код заблокирован после неверных попыток — запросите новый") }
                clock.instant().isAfter(session.expiresAt) -> session.also { failure = ConflictException("Срок действия кода истёк — запросите новый") }
                !MessageDigest.isEqual(session.code.toByteArray(), request.code.trim().toByteArray()) -> {
                    val left = session.attemptsLeft - 1
                    failure =
                        BadRequestException(if (left > 0) "Неверный код, осталось попыток: $left" else "Неверный код. Попытки закончились — запросите новый")
                    session.copy(attemptsLeft = left)
                }
                else -> session.copy(signatureId = UUID.randomUUID(), signedAt = clock.instant()).also { outcome = it }
            }
        }
        failure?.let { throw it }
        return outcome!!
    }

    private fun sha256(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    private companion object {
        val TTL: Duration = Duration.ofMinutes(5)
        const val MAX_ATTEMPTS = 5
    }
}
