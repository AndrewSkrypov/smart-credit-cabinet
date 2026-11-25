package ru.creditcabinet.offer.application

interface OfferTransactions {
    fun <T : Any> execute(action: () -> T): T
}
