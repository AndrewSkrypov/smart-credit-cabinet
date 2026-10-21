package ru.creditcabinet.common.exception

import org.springframework.http.HttpStatus

class NotFoundException(message: String) : ApiException(HttpStatus.NOT_FOUND, message)
