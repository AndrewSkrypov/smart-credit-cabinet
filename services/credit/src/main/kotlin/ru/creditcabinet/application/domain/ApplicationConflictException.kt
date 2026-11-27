package ru.creditcabinet.application.domain

class ApplicationConflictException(override val message: String) : RuntimeException(message)
