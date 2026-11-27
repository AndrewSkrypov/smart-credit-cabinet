package ru.creditcabinet.auth.infrastructure

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import ru.creditcabinet.auth.application.PersonalDataConsents
import java.util.UUID

/** The database rejects changes to previously recorded consent. */
@Component
class JdbcPersonalDataConsents(private val jdbc: NamedParameterJdbcTemplate) : PersonalDataConsents {
    override fun record(userId: UUID) {
        jdbc.update(
            "insert into personal_data_consents (id, user_id, document, version) values (:id, :user, :document, :version)",
            mapOf("id" to UUID.randomUUID(), "user" to userId, "document" to "PERSONAL_DATA", "version" to PERSONAL_DATA_VERSION),
        )
    }

    private companion object {
        /** The edition published at /legal/consent. Update it together with the text. */
        const val PERSONAL_DATA_VERSION = "2026-10-21"
    }
}
