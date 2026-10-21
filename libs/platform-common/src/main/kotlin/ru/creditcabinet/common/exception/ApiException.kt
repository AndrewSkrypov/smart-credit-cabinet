package ru.creditcabinet.common.exception

import org.springframework.http.HttpStatus

open class ApiException(
    val httpStatus: HttpStatus,
    override val message: String,
) : RuntimeException(message)
