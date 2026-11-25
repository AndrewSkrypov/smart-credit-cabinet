package ru.creditcabinet.notification.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.notification.application.NotificationPreference
import ru.creditcabinet.notification.application.NotificationSettings
import ru.creditcabinet.notification.application.NotificationSettingsOperations

@Tag(name = "Настройки уведомлений")
@RestController
@RequestMapping("/api/v1/notifications/settings")
class NotificationSettingsController(private val settings: NotificationSettingsOperations) {
    @Operation(summary = "Настройки моих уведомлений и доступность почтового канала")
    @GetMapping
    fun get(): NotificationSettings = settings.get(CurrentCaller.claims().userId)

    @Operation(summary = "Изменить дублирование важных уведомлений на почту")
    @PutMapping
    fun save(@RequestBody preference: NotificationPreference): NotificationSettings =
        settings.save(CurrentCaller.claims().userId, preference)
}
