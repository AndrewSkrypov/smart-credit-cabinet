package ru.creditcabinet.application.domain

class ApplicationConflictException(override val message: String, val code: String? = null) : RuntimeException(message)
