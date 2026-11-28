package ru.creditcabinet.assistant.domain

enum class AnswerMode { MODEL, RULES }

data class Turn(val role: String, val text: String)

data class Answer(val text: String, val mode: AnswerMode, val sources: List<String>, val link: AnswerLink? = null)

data class ReferencePreview(val text: String, val source: String)

/** A button under an answer: where the cabinet continues what the assistant started. */
data class AnswerLink(val label: String, val path: String)
