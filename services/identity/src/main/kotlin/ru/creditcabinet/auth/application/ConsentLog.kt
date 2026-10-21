package ru.creditcabinet.auth.application

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * The record of consents to the processing of personal data (152-FZ): who accepted which
 * edition of the document, and when. Entries are only added, never changed (a trigger in the
 * database refuses updates).
 */
@Component
class ConsentLog(private val jdbc: NamedParameterJdbcTemplate) {
    fun record(userId: UUID, document: String, version: String) {
        jdbc.update(
            "insert into personal_data_consents (id, user_id, document, version) values (:id, :user, :document, :version)",
            mapOf("id" to UUID.randomUUID(), "user" to userId, "document" to document, "version" to version),
        )
    }

    companion object {
        const val PERSONAL_DATA = "PERSONAL_DATA"

        /** The edition published at /legal/consent; bump it together with the text. */
        const val PERSONAL_DATA_VERSION = "2026-10-21"
    }
}
