package ru.creditcabinet.notification

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.mail.MailCapabilities
import ru.creditcabinet.common.security.CurrentCaller
import java.util.UUID

data class NotificationPreference(val emailEnabled: Boolean = false, val version: Long = 0)
data class NotificationSettings(val emailAvailable: Boolean, val emailEnabled: Boolean, val version: Long)

@Component
class NotificationPreferences(private val jdbc: NamedParameterJdbcTemplate) {
    fun get(userId: UUID): NotificationPreference =
        jdbc.query("select email_enabled,version from notification_preferences where user_id=:id", mapOf("id" to userId)) { rs, _ ->
            NotificationPreference(rs.getBoolean("email_enabled"), rs.getLong("version"))
        }.firstOrNull() ?: NotificationPreference()

    @Transactional
    fun save(userId: UUID, preference: NotificationPreference): NotificationPreference {
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
        ) { rs, _ -> rs.getLong("version") }.firstOrNull() ?: throw ConflictException("Настройки уже изменились — обновите страницу")
        return NotificationPreference(preference.emailEnabled, updated)
    }
}

@Tag(name = "Настройки уведомлений")
@RestController
@RequestMapping("/api/v1/notifications/settings")
class NotificationSettingsController(private val preferences: NotificationPreferences, private val mail: MailCapabilities) {
    @Operation(summary = "Настройки моих уведомлений и доступность почтового канала")
    @GetMapping
    fun get(): NotificationSettings {
        val value = preferences.get(CurrentCaller.claims().userId)
        return NotificationSettings(mail.enabled(), value.emailEnabled, value.version)
    }

    @Operation(summary = "Изменить дублирование важных уведомлений на почту")
    @PutMapping
    fun save(@RequestBody preference: NotificationPreference): NotificationSettings {
        val available = mail.enabled()
        if (preference.emailEnabled && !available) throw ConflictException("Почтовый канал на этом стенде выключен")
        val value = preferences.save(CurrentCaller.claims().userId, preference)
        return NotificationSettings(available, value.emailEnabled, value.version)
    }
}
