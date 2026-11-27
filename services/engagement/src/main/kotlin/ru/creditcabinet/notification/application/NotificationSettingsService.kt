package ru.creditcabinet.notification.application

import org.springframework.stereotype.Service
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.mail.MailCapabilities
import java.util.UUID

data class NotificationPreference(val emailEnabled: Boolean = false, val version: Long = 0)
data class NotificationSettings(val emailAvailable: Boolean, val emailEnabled: Boolean, val version: Long)

interface NotificationPreferences {
    fun get(userId: UUID): NotificationPreference
    fun save(userId: UUID, preference: NotificationPreference): NotificationPreference
}

interface NotificationSettingsOperations {
    fun get(userId: UUID): NotificationSettings

    fun save(userId: UUID, preference: NotificationPreference): NotificationSettings
}

@Service
class NotificationSettingsService(private val preferences: NotificationPreferences, private val mail: MailCapabilities) : NotificationSettingsOperations {
    override fun get(userId: UUID): NotificationSettings {
        val value = preferences.get(userId)
        return NotificationSettings(mail.enabled(), value.emailEnabled, value.version)
    }

    override fun save(userId: UUID, preference: NotificationPreference): NotificationSettings {
        val available = mail.enabled()
        if (preference.emailEnabled && !available) throw ConflictException("Почтовый канал на этом стенде выключен")
        val value = preferences.save(userId, preference)
        return NotificationSettings(available, value.emailEnabled, value.version)
    }
}
