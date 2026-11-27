package ru.creditcabinet.application.application

interface CreditTransactions {
    val active: Boolean

    fun <T : Any> execute(action: () -> T): T

    fun executeWithoutResult(action: () -> Unit) {
        execute(action)
    }
}
