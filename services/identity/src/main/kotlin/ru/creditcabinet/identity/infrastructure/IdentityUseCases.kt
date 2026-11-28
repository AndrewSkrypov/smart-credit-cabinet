package ru.creditcabinet.identity.infrastructure

import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.creditcabinet.access.application.CompanyAccessService
import ru.creditcabinet.access.application.PasswordAccessService
import ru.creditcabinet.access.application.ProfileAccessService
import ru.creditcabinet.access.application.SecurityMail
import ru.creditcabinet.access.domain.AccountAccessPort
import ru.creditcabinet.access.domain.AccountLinkIssuer
import ru.creditcabinet.access.domain.RecoveryLimits
import ru.creditcabinet.agent.application.AgentClientService
import ru.creditcabinet.agent.application.CompanyAgentMandates
import ru.creditcabinet.agent.domain.AgentClientPort
import ru.creditcabinet.agent.domain.AgentMandatePort
import ru.creditcabinet.auth.application.AuthService
import ru.creditcabinet.auth.application.PasswordHashes
import ru.creditcabinet.auth.application.PersonalDataConsents
import ru.creditcabinet.auth.application.SessionTokens
import ru.creditcabinet.auth.application.SignInLimits
import ru.creditcabinet.company.application.CompanyQueries
import ru.creditcabinet.company.domain.CompanyDataPort
import ru.creditcabinet.company.domain.CompanyRegistry
import ru.creditcabinet.identity.application.IdentityTransactions
import ru.creditcabinet.user.application.UserQueries
import ru.creditcabinet.user.domain.UserDataPort
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class IdentityUseCases(private val clocks: ObjectProvider<Clock>) {
    @Bean
    fun authService(
        users: UserDataPort,
        companies: CompanyDataPort,
        registry: CompanyRegistry,
        tokens: SessionTokens,
        signInLimits: SignInLimits,
        consents: PersonalDataConsents,
        transactions: IdentityTransactions,
    ) = AuthService(users, companies, registry, tokens, signInLimits, consents, transactions)

    @Bean
    fun companyAccessService(
        store: AccountAccessPort,
        links: AccountLinkIssuer,
        mail: SecurityMail,
        passwords: PasswordHashes,
        tokens: SessionTokens,
        consents: PersonalDataConsents,
        transactions: IdentityTransactions,
    ) = CompanyAccessService(store, links, mail, passwords, tokens, consents, transactions, clock())

    @Bean
    fun passwordAccessService(
        store: AccountAccessPort,
        passwords: PasswordHashes,
        links: AccountLinkIssuer,
        mail: SecurityMail,
        throttle: RecoveryLimits,
        transactions: IdentityTransactions,
    ) = PasswordAccessService(store, passwords, links, mail, throttle, transactions, clock())

    @Bean
    fun profileAccessService(
        store: AccountAccessPort,
        passwords: PasswordHashes,
        tokens: SessionTokens,
        transactions: IdentityTransactions,
    ) = ProfileAccessService(store, passwords, tokens, transactions, clock())

    @Bean
    fun companyAgentMandates(
        accounts: AccountAccessPort,
        mandates: AgentMandatePort,
        transactions: IdentityTransactions,
    ) = CompanyAgentMandates(accounts, mandates, transactions, clock())

    @Bean
    fun agentClientService(agentClients: AgentClientPort, companies: CompanyDataPort) = AgentClientService(agentClients, companies)

    @Bean
    fun userQueries(users: UserDataPort) = UserQueries(users)

    @Bean
    fun companyQueries(companies: CompanyDataPort, users: UserDataPort) = CompanyQueries(companies, users)

    private fun clock(): Clock = clocks.getIfAvailable { Clock.systemUTC() }
}
