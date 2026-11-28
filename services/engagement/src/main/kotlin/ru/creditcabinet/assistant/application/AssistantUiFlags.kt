package ru.creditcabinet.assistant.application

import java.util.UUID

fun interface AssistantUiFlags {
    fun isKeshaEnabled(userId: UUID): Boolean
}
