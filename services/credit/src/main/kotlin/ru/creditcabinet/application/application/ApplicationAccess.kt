package ru.creditcabinet.application.application

import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.common.security.TokenClaims
import java.util.UUID

interface ApplicationAccess {
    fun get(id: UUID): LoanApplication

    fun own(id: UUID): Pair<TokenClaims, LoanApplication>
}
