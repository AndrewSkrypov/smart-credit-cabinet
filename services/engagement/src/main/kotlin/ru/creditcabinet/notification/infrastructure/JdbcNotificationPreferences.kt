package ru.creditcabinet.notification.infrastructure

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.notification.application.NotificationPreference
import ru.creditcabinet.notification.application.NotificationPreferences
import ru.creditcabinet.notification.application.NotificationSettingsConflict
import java.util.UUID

@Component
class JdbcNotificationPreferences(private val jdbc: NamedParameterJdbcTemplate) : NotificationPreferences {
    override fun get(userId: UUID): NotificationPreference =
        jdbc.query("select email_enabled,version from notification_preferences where user_id=:id", mapOf("id" to userId)) { rs, _ ->
            NotificationPreference(rs.getBoolean("email_enabled"), rs.getLong("version"))
        }.firstOrNull() ?: NotificationPreference()

    @Transactional
    override fun save(userId: UUID, preference: NotificationPreference): NotificationPreference {
        val result = jdbc.query(
            """
            insert into notification_preferences (user_id,email_enabled,version)
            select :id,:enabled,1 where :version=0
            on conflict (user_id) do nothing returning version
            """.trimIndent(),
            mapOf("id" to userId, "enabled" to preference.emailEnabled, "version" to preference.version),
        ) { rs, _ -> rs.getLong("version") }.firstOrNull()
        if (result != null) return NotificationPreference(preference.emailEnabled, result)
        val updated = jdbc.query(
            """
            update notification_preferences set email_enabled=:enabled,version=version+1,updated_at=now()
            where user_id=:id and version=:version returning version
            """.trimIndent(),
            mapOf("id" to userId, "enabled" to preference.emailEnabled, "version" to preference.version),
        ) { rs, _ -> rs.getLong("version") }.firstOrNull() ?: throw NotificationSettingsConflict("Настройки уже изменились — обновите страницу")
        return NotificationPreference(preference.emailEnabled, updated)
    }
}
