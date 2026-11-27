package ru.creditcabinet.agent.application

import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import ru.creditcabinet.application.domain.CompanySnapshot
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.integration.domain.BankDataPort
import ru.creditcabinet.integration.domain.SourceUnavailableException

@Component
class AgentClientEligibility(private val bankData: BankDataPort) {
    fun isEligible(company: CompanySnapshot): Boolean =
        try {
            bankData.bank(company.inn).client
        } catch (_: SourceUnavailableException) {
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Статус клиента банка временно недоступен")
        }

    fun requireEligible(company: CompanySnapshot) {
        if (!isEligible(company)) throw ConflictException("Агентский канал доступен действующим клиентам банка")
    }
}
