package ru.creditcabinet.application.infrastructure

import org.springframework.stereotype.Component
import ru.creditcabinet.application.application.CreditCaller
import ru.creditcabinet.common.security.CurrentCaller

@Component
class SpringCreditCaller : CreditCaller {
    override fun claims() = CurrentCaller.claims()

    override fun requireRole(vararg roles: String) = CurrentCaller.requireRole(*roles)

    override fun requireClient() = CurrentCaller.requireClient()

    override fun requireBankStaff() = CurrentCaller.requireBankStaff()
}
