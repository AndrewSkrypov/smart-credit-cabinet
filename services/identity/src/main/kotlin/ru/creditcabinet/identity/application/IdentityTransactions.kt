package ru.creditcabinet.identity.application

interface IdentityTransactions {
    fun <T : Any> write(operation: () -> T): T
    fun <T : Any> read(operation: () -> T): T
}
