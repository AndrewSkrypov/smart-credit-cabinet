package ru.creditcabinet.auth.application

import ru.creditcabinet.auth.domain.PasswordPolicy
import ru.creditcabinet.common.model.Inn
import ru.creditcabinet.company.domain.Company
import ru.creditcabinet.company.domain.CompanyDataPort
import ru.creditcabinet.company.domain.CompanyRegistry
import ru.creditcabinet.company.domain.RegistryAnswer
import ru.creditcabinet.identity.application.IdentityTransactions
import ru.creditcabinet.identity.domain.IdentityFailure.Conflict
import ru.creditcabinet.identity.domain.IdentityFailure.Forbidden
import ru.creditcabinet.identity.domain.IdentityFailure.InvalidInput
import ru.creditcabinet.identity.domain.IdentityFailure.Unauthorized
import ru.creditcabinet.user.domain.User
import ru.creditcabinet.user.domain.UserDataPort
import ru.creditcabinet.user.domain.UserRegistrationConflict
import ru.creditcabinet.user.domain.UserRole
import ru.creditcabinet.user.domain.UserStatus
import java.time.Instant

class AuthService(
    private val users: UserDataPort,
    private val companies: CompanyDataPort,
    private val registry: CompanyRegistry,
    private val tokens: SessionTokens,
    private val signInLimits: SignInLimits,
    private val consents: PersonalDataConsents,
    private val transactions: IdentityTransactions,
) {
    fun signIn(rawEmail: String, password: String, address: String): IdentitySession =
        session(authenticateUser(rawEmail, password, address))

    /** Verifies first-party credentials without issuing a browser session token. */
    fun authenticateUser(rawEmail: String, password: String, address: String): User {
        val email = rawEmail.trim().lowercase()
        signInLimits.check(email, address)
        // An unknown e-mail costs the same password check as a known one, so the answer time
        // does not tell which addresses have accounts.
        val user = users.authenticate(email, password)
        if (user == null) {
            signInLimits.recordFailure(email)
            throw Unauthorized("Неверный email или пароль")
        }
        signInLimits.reset(email)
        if (user.status != UserStatus.ACTIVE) throw Forbidden(BLOCKED)
        val at = Instant.now()
        if (!users.recordSignIn(user.id, user.credentialsVersion, at)) throw Unauthorized(REVOKED)
        return user.copy(lastSignInAt = at)
    }

    /**
     * Registers a business and the person who connects it. That person represents the company
     * and may sign for it; colleagues are added by them later. A company is connected once:
     * a second sign-up with the same ИНН is refused, so nobody joins someone else's company
     * by knowing its ИНН. The name comes from ЕГРЮЛ/ЕГРИП after checking tax registration in ЕГРН.
     * A confirmed missing record is refused, a registry outage does not stop the sign-up.
     */
    fun signUp(fullName: String, rawEmail: String, password: String, rawInn: String, personalDataConsent: Boolean): IdentitySession = transactions.write {
        if (!personalDataConsent) throw InvalidInput("Без согласия на обработку персональных данных зарегистрироваться нельзя")
        PasswordPolicy.check(password)
        val inn = Inn.parse(rawInn).value
        // Addresses are stored lowercase: sign-in looks them up that way.
        val email = rawEmail.trim().lowercase()
        if (users.findByEmail(email) != null) throw Conflict("Пользователь с таким email уже зарегистрирован")
        if (companies.findByInn(inn) != null) throw Conflict(COMPANY_TAKEN)
        val name = when (val answer = registry.lookup(inn)) {
            is RegistryAnswer.Found -> answer.shortName
            RegistryAnswer.NotFound -> throw InvalidInput("Компания с таким ИНН не найдена в ЕГРЮЛ и ЕГРИП — проверьте номер")
            RegistryAnswer.TaxpayerNotFound -> throw InvalidInput("ИНН не найден в ЕГРН ФНС — проверьте номер")
            RegistryAnswer.Unavailable -> null
        }
        val company = companies.save(Company(inn = inn, name = name))
        val user = try {
            users.save(
                User(fullName = fullName.trim(), email = email, role = UserRole.CLIENT, companyId = company.id, canSign = true),
                password,
            )
        } catch (exception: UserRegistrationConflict) {
            // The same e-mail or ИНН registered at the same moment by another request.
            throw Conflict("Такой пользователь или компания уже зарегистрированы")
        }
        consents.record(user.id)
        session(user)
    }

    fun session(token: String): IdentitySession = session(currentUser(token))

    /** The user behind a token, checked against the database: blocked or revoked tokens stop here. */
    fun currentUser(token: String): User {
        val actor = tokens.read(token)
        val user = users.findById(actor.userId) ?: throw Unauthorized()
        if (actor.credentialsVersion != user.credentialsVersion) throw Unauthorized(REVOKED)
        if (user.status != UserStatus.ACTIVE) throw Forbidden(BLOCKED)
        return user
    }

    private fun session(user: User) = IdentitySession(tokens.issue(user), user)

    companion object {
        const val BLOCKED = "Учётная запись заблокирована. Обратитесь в банк."
        const val REVOKED = "Сессия больше не действует — войдите заново"
        const val COMPANY_TAKEN = "Компания с этим ИНН уже подключена. Попросите её представителя добавить вас в кабинет."
    }
}
