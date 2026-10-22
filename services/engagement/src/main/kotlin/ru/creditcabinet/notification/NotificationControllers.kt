package ru.creditcabinet.notification

import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@Tag(name = "Уведомления в кабинете")
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(private val service: NotificationService) {
    @Operation(summary = "Мои уведомления, новые сверху")
    @GetMapping
    fun list(@RequestParam(defaultValue = "0") page: Int, @RequestParam(defaultValue = "20") size: Int): NotificationPage = service.page(page, size)

    @Operation(summary = "Число непрочитанных уведомлений текущего пользователя")
    @GetMapping("/unread-count")
    fun unread(): Map<String, Int> = mapOf("unread" to service.unread())

    @Operation(summary = "Отметить своё уведомление прочитанным; чужое возвращает 404")
    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun read(@PathVariable id: UUID) = service.markRead(id)

    @Operation(summary = "Отметить все свои уведомления прочитанными")
    @PostMapping("/read-all")
    fun readAll(): Map<String, Int> = mapOf("updated" to service.markAllRead())
}

data class InternalNotificationRequest(@field:Valid @field:Size(min = 1, max = 100) val items: List<Item>) {
    data class Item(
        val recipientId: UUID? = null,
        val recipientCompanyId: UUID? = null,
        val recipientRole: String? = null,
        val type: NotificationType,
        @field:NotBlank @field:Size(max = 255) val title: String,
        @field:NotBlank @field:Size(max = 1000) val body: String,
        @field:Size(max = 512) val link: String? = null,
        @field:Size(max = 255) val dedupKey: String? = null,
    )
}

/** For credit. Not routed by the gateway (/internal is closed there). */
@Hidden
@RestController
@RequestMapping("/internal/v1/notifications")
class InternalNotificationController(private val service: NotificationService) {
    @PostMapping
    fun deliver(@Valid @RequestBody request: InternalNotificationRequest): Map<String, Int> =
        mapOf(
            "stored" to service.deliver(
                request.items.map {
                    NewNotification(Audience(it.recipientId, it.recipientCompanyId, it.recipientRole), it.type, it.title, it.body, it.link, it.dedupKey)
                },
            ),
        )
}
