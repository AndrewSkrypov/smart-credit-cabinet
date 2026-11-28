package ru.creditcabinet.assistant.application

data class ChatMessage(val role: String, val content: String)

/** A language model the assistant may use; null answers mean "not available now, use the rules". */
interface LanguageModel {
    val name: String
    fun available(): Boolean
    fun chat(messages: List<ChatMessage>): String?
}
