package ru.creditcabinet.notification.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.notification.application.NotificationSettingsConflict
import ru.creditcabinet.notification.application.NotificationSettingsOperations
import ru.creditcabinet.notification.application.NotificationPreference as Preference
import ru.creditcabinet.notification.application.NotificationSettings as Settings

data class NotificationPreference(val emailEnabled: Boolean = false, val version: Long = 0)

data class NotificationSettings(val emailAvailable: Boolean, val emailEnabled: Boolean, val version: Long)

private fun Settings.toResponse() = NotificationSettings(emailAvailable, emailEnabled, version)

@Tag(name = "Настройки уведомлений")
@RestController
@RequestMapping("/api/v1/notifications/settings")
class NotificationSettingsController(private val settings: NotificationSettingsOperations) {
    @Operation(summary = "Настройки моих уведомлений и доступность почтового канала")
    @GetMapping
    fun get(): NotificationSettings = settings.get(CurrentCaller.claims().userId).toResponse()

    @Operation(summary = "Изменить дублирование важных уведомлений на почту")
    @PutMapping
    fun save(@RequestBody preference: NotificationPreference): NotificationSettings = try {
        settings.save(CurrentCaller.claims().userId, Preference(preference.emailEnabled, preference.version)).toResponse()
    } catch (conflict: NotificationSettingsConflict) {
        throw ConflictException(conflict.message)
    }
}
