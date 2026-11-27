package ru.creditcabinet.application.application

interface CreditTransactions {
    fun <T : Any> execute(action: () -> T): T

    fun executeWithoutResult(action: () -> Unit) {
        execute(action)
    }
}
