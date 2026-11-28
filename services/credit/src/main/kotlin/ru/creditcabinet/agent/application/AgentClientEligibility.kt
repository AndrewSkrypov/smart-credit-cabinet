package ru.creditcabinet.agent.application

import org.springframework.stereotype.Component
import ru.creditcabinet.application.application.CreditFailure.Conflict
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.integration.domain.BankDataPort
import ru.creditcabinet.integration.domain.SourceUnavailableException

class AgentClientStatusUnavailable : RuntimeException("Статус клиента банка временно недоступен")

@Component
class AgentClientEligibility(private val bankData: BankDataPort) {
    fun isEligible(company: CompanySnapshot): Boolean =
        try {
            bankData.bank(company.inn).client
        } catch (_: SourceUnavailableException) {
            throw AgentClientStatusUnavailable()
        }

    fun requireEligible(company: CompanySnapshot) {
        if (!isEligible(company)) throw Conflict("Агентский канал доступен действующим клиентам банка")
    }
}
