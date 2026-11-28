package ru.creditcabinet.application.application

import ru.creditcabinet.common.security.TokenClaims
import java.util.UUID

interface CreditCaller {
    fun claims(): TokenClaims

    fun requireRole(vararg roles: String): TokenClaims

    fun requireClient(): Pair<TokenClaims, UUID>

    fun requireBankStaff(): TokenClaims

    companion object {
        const val FORBIDDEN = "Недостаточно прав для этого действия"
    }
}
