package ru.creditcabinet.auth.controller

import ru.creditcabinet.auth.application.IdentityActor
import ru.creditcabinet.auth.application.IdentitySession
import ru.creditcabinet.auth.dto.UserSessionResponse
import ru.creditcabinet.common.exception.UnauthorizedException
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.user.dto.UserResponse

internal fun TokenClaims.actor() = IdentityActor(userId, credentialsVersion, companyId, isClient)

internal fun IdentitySession.toResponse() = UserSessionResponse(token, UserResponse.of(user))

internal fun sessionToken(authorization: String?): String =
    authorization?.removePrefix("Bearer ")?.takeIf { it.isNotBlank() } ?: throw UnauthorizedException()
