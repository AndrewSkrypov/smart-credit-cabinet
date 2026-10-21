package ru.creditcabinet.auth.application

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.auth.dto.SignInRequest
import ru.creditcabinet.auth.dto.SignUpRequest
import ru.creditcabinet.auth.dto.UserSessionResponse
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.ForbiddenException
import ru.creditcabinet.common.exception.UnauthorizedException
import ru.creditcabinet.common.model.Inn
import ru.creditcabinet.company.domain.Company
import ru.creditcabinet.company.domain.CompanyDataPort
import ru.creditcabinet.company.domain.CompanyRegistry
import ru.creditcabinet.company.domain.RegistryAnswer
import ru.creditcabinet.user.domain.User
import ru.creditcabinet.user.domain.UserDataPort
import ru.creditcabinet.user.domain.UserRole
import ru.creditcabinet.user.domain.UserStatus
import ru.creditcabinet.user.dto.UserResponse
import java.time.Instant

@Service
class AuthService(
    private val users: UserDataPort,
    private val companies: CompanyDataPort,
    private val registry: CompanyRegistry,
    private val jwtService: JwtService,
    private val signInThrottle: SignInThrottle,
    private val consentLog: ConsentLog,
) {
    fun signIn(request: SignInRequest): UserSessionResponse {
        val email = request.email.trim().lowercase()
        signInThrottle.check(email)
        // An unknown e-mail costs the same password check as a known one, so the answer time
        // does not tell which addresses have accounts.
        val passwordMatches = users.verifyPassword(email, request.password)
        val user = users.findByEmail(email)?.takeIf { passwordMatches }
        if (user == null) {
            signInThrottle.recordFailure(email)
            throw UnauthorizedException("Неверный email или пароль")
        }
        signInThrottle.reset(email)
        if (user.status != UserStatus.ACTIVE) throw ForbiddenException(BLOCKED)
        return session(users.save(user.copy(lastSignInAt = Instant.now())))
    }

    /**
     * Registers a business and the person who connects it. That person represents the company
     * and may sign for it; colleagues are added by them later. A company is connected once:
     * a second sign-up with the same ИНН is refused, so nobody joins someone else's company
     * by knowing its ИНН. The name comes from ЕГРЮЛ/ЕГРИП, so the client does not type it; a ИНН
     * the registry does not know is refused, a registry outage does not stop the sign-up.
     */
    @Transactional
    fun signUp(request: SignUpRequest): UserSessionResponse {
        PasswordPolicy.check(request.password)
        val inn = Inn.parse(request.inn).value
        // Addresses are stored lowercase: sign-in looks them up that way.
        val email = request.email.trim().lowercase()
        if (users.findByEmail(email) != null) throw ConflictException("Пользователь с таким email уже зарегистрирован")
        if (companies.findByInn(inn) != null) throw ConflictException(COMPANY_TAKEN)
        val name = when (val answer = registry.lookup(inn)) {
            is RegistryAnswer.Found -> answer.shortName
            RegistryAnswer.NotFound -> throw BadRequestException("Компания с таким ИНН не найдена в ЕГРЮЛ и ЕГРИП — проверьте номер")
            RegistryAnswer.Unavailable -> null
        }
        val company = companies.save(Company(inn = inn, name = name))
        val user = try {
            users.save(
                User(fullName = request.fullName.trim(), email = email, role = UserRole.CLIENT, companyId = company.id, canSign = true),
                request.password,
            )
        } catch (exception: DataIntegrityViolationException) {
            // The same e-mail or ИНН registered at the same moment by another request.
            throw ConflictException("Такой пользователь или компания уже зарегистрированы")
        }
        consentLog.record(user.id, ConsentLog.PERSONAL_DATA, ConsentLog.PERSONAL_DATA_VERSION)
        return session(user)
    }

    fun session(authorization: String?): UserSessionResponse = session(currentUser(authorization))

    /** The user behind a token, checked against the database: blocked or revoked tokens stop here. */
    fun currentUser(authorization: String?): User {
        val rawToken = authorization?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() } ?: throw UnauthorizedException()
        val claims = jwtService.parseClaims(rawToken)
        val user = users.findById(claims.userId) ?: throw UnauthorizedException()
        if (claims.credentialsVersion < user.credentialsVersion) throw UnauthorizedException(REVOKED)
        if (user.status != UserStatus.ACTIVE) throw ForbiddenException(BLOCKED)
        return user
    }

    private fun session(user: User) = UserSessionResponse(token = jwtService.issue(user), user = UserResponse.of(user))

    companion object {
        const val BLOCKED = "Учётная запись заблокирована. Обратитесь в банк."
        const val REVOKED = "Сессия больше не действует — войдите заново"
        const val COMPANY_TAKEN = "Компания с этим ИНН уже подключена. Попросите её представителя добавить вас в кабинет."
    }
}
