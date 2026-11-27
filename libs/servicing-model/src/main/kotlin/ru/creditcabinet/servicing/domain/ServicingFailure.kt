package ru.creditcabinet.servicing.domain

/** Business failures carry no transport/framework types. */
open class ServicingFailure(val code: String, message: String) : RuntimeException(message)
class ServicingBadRequest(message: String) : ServicingFailure("BAD_REQUEST", message)
class ServicingConflict(message: String) : ServicingFailure("CONFLICT", message)
class ServicingNotFound(message: String) : ServicingFailure("NOT_FOUND", message)
class ServicingUnavailable(message: String) : ServicingFailure("UNAVAILABLE", message)
