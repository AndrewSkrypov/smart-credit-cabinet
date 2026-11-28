package ru.creditcabinet.company.domain

/** The result of checking an ИНН in ЕГРЮЛ/ЕГРИП and ЕГРН at sign-up. */
sealed interface RegistryAnswer {
    data class Found(val shortName: String) : RegistryAnswer

    data object NotFound : RegistryAnswer

    data object TaxpayerNotFound : RegistryAnswer

    /** A check could not be completed: sign-up goes on without a registry-confirmed name. */
    data object Unavailable : RegistryAnswer
}

interface CompanyRegistry {
    fun lookup(inn: String): RegistryAnswer
}
