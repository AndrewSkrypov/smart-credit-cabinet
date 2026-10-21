package ru.creditcabinet.common.exception

import org.springframework.http.HttpStatus

class ForbiddenException(message: String) : ApiException(HttpStatus.FORBIDDEN, message)
