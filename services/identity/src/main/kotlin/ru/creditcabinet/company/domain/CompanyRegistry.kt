package ru.creditcabinet.company.domain

/** What the state registry (ЕГРЮЛ/ЕГРИП) says about a ИНН at sign-up. */
sealed interface RegistryAnswer {
    data class Found(val shortName: String) : RegistryAnswer

    data object NotFound : RegistryAnswer

    /** The registry did not answer: sign-up goes on, the name is filled in later by the credit service. */
    data object Unavailable : RegistryAnswer
}

interface CompanyRegistry {
    fun lookup(inn: String): RegistryAnswer
}
