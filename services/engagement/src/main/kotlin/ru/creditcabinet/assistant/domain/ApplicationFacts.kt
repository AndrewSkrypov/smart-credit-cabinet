package ru.creditcabinet.assistant.domain

/** What the assistant may know about one application, in words; facts only, no guesses. */
data class ApplicationFacts(
    val number: String,
    val status: String,
    val lines: List<String>,
    val offer: String?,
    /** The cabinet's own estimate at the base rate: a calculation, never the bank's terms. */
    val estimate: String? = null,
    val refusal: List<String>,
    val missingDocuments: List<String>,
    val deadline: String?,
) {
    fun asText(): String = (listOf("Заявка № $number, статус: $status") + lines).joinToString("\n")
}
