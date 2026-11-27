package ru.creditcabinet.application.infrastructure

import org.springframework.stereotype.Component
import ru.creditcabinet.application.application.CreditCaller
import ru.creditcabinet.application.application.CreditFailure
import ru.creditcabinet.common.exception.ForbiddenException
import ru.creditcabinet.common.exception.UnauthorizedException
import ru.creditcabinet.common.security.CurrentCaller

@Component
class SpringCreditCaller : CreditCaller {
    override fun claims() = caller(CurrentCaller::claims)

    override fun requireRole(vararg roles: String) = caller { CurrentCaller.requireRole(*roles) }

    override fun requireClient() = caller(CurrentCaller::requireClient)

    override fun requireBankStaff() = caller(CurrentCaller::requireBankStaff)

    private inline fun <T> caller(action: () -> T): T = try {
        action()
    } catch (failure: ForbiddenException) {
        throw CreditFailure.Forbidden(failure.message)
    } catch (failure: UnauthorizedException) {
        throw CreditFailure.Unauthenticated(failure.message)
    }
}
