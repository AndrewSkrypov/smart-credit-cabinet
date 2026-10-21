package ru.creditcabinet.common.security

import org.springframework.security.core.context.SecurityContextHolder
import ru.creditcabinet.common.exception.ForbiddenException
import ru.creditcabinet.common.exception.UnauthorizedException
import java.util.UUID

/** The caller of the current request, as the verified token describes them. */
object CurrentCaller {
    fun claims(): TokenClaims =
        SecurityContextHolder.getContext().authentication?.principal as? TokenClaims ?: throw UnauthorizedException()

    /** 403 unless the caller has one of [roles]. Role checks belong on the server: a hidden button protects nothing. */
    fun requireRole(vararg roles: String): TokenClaims =
        claims().also { if (it.role !in roles) throw ForbiddenException(FORBIDDEN) }

    /** A client and the company they act for; staff and clients without a company get 403. */
    fun requireClient(): Pair<TokenClaims, UUID> {
        val claims = requireRole(Roles.CLIENT)
        val companyId = claims.companyId ?: throw ForbiddenException("Сначала подключите компанию")
        return claims to companyId
    }

    fun requireBankStaff(): TokenClaims =
        claims().also { if (!it.isBankStaff) throw ForbiddenException(FORBIDDEN) }

    const val FORBIDDEN = "Недостаточно прав для этого действия"
}
