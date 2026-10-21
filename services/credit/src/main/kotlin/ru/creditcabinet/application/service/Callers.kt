package ru.creditcabinet.application.service

import ru.creditcabinet.application.domain.Actor
import ru.creditcabinet.application.domain.ActorType
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims

/** The caller as an actor of the state machine; a risk administrator only reads, so has none. */
fun TokenClaims.toActor(): Actor? =
    when (role) {
        Roles.CLIENT -> Actor(ActorType.CLIENT, userId)
        Roles.MANAGER -> Actor(ActorType.MANAGER, userId)
        Roles.UNDERWRITER -> Actor(ActorType.UNDERWRITER, userId)
        else -> null
    }

/**
 * Whether the caller may see the application: a client — those of their own company, bank
 * staff — all but drafts. Everything else is answered 404, so ids of others do not leak.
 */
fun TokenClaims.canSee(application: LoanApplication): Boolean =
    when {
        isClient -> companyId != null && application.company.id == companyId
        isBankStaff -> application.visibleToBank
        else -> false
    }

fun notFound() = NotFoundException("Заявка не найдена")
