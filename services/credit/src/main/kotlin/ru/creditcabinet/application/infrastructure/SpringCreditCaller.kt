package ru.creditcabinet.application.infrastructure

import org.springframework.stereotype.Component
import ru.creditcabinet.application.application.CreditCaller
import ru.creditcabinet.common.security.CurrentCaller

@Component
class SpringCreditCaller : CreditCaller {
    override fun claims() = creditPortCall(CurrentCaller::claims)

    override fun requireRole(vararg roles: String) = creditPortCall { CurrentCaller.requireRole(*roles) }

    override fun requireClient() = creditPortCall(CurrentCaller::requireClient)

    override fun requireBankStaff() = creditPortCall(CurrentCaller::requireBankStaff)
}
