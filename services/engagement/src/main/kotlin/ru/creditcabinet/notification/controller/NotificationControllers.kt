package ru.creditcabinet.notification.controller

import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.notifications.NotificationEmail
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.notification.application.Audience
import ru.creditcabinet.notification.application.NewNotification
import ru.creditcabinet.notification.application.NotificationNotFound
import ru.creditcabinet.notification.application.NotificationUseCases
import ru.creditcabinet.notification.domain.NotificationType
import java.time.Instant
import java.util.UUID
import ru.creditcabinet.notification.application.NotificationPage as Page

data class NotificationPage(val items: List<NotificationView>, val page: Int, val size: Int, val total: Int, val unread: Int)

data class NotificationView(
    val id: UUID,
    val type: NotificationType,
    val title: String,
    val body: String,
    val link: String?,
    val read: Boolean,
    val createdAt: Instant,
)

private fun Page.toResponse() = NotificationPage(
    items.map { NotificationView(it.id, it.type, it.title, it.body, it.link, it.read, it.createdAt) },
    page,
    size,
    total,
    unread,
)

@Tag(name = "Уведомления в кабинете")
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationController(private val service: NotificationUseCases) {
    @Operation(summary = "Мои уведомления, новые сверху")
    @GetMapping
    fun list(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        request: HttpServletRequest,
    ): NotificationPage = service.page(CurrentCaller.claims(), page, size, request.getHeader(HttpHeaders.AUTHORIZATION)).toResponse()

    @Operation(summary = "Число непрочитанных уведомлений текущего пользователя")
    @GetMapping("/unread-count")
    fun unread(request: HttpServletRequest): Map<String, Int> = mapOf(
        "unread" to service.unread(CurrentCaller.claims(), request.getHeader(HttpHeaders.AUTHORIZATION)),
    )

    @Operation(summary = "Отметить своё уведомление прочитанным; чужое возвращает 404")
    @PostMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun read(@PathVariable id: UUID, request: HttpServletRequest) = try {
        service.markRead(CurrentCaller.claims(), id, request.getHeader(HttpHeaders.AUTHORIZATION))
    } catch (missing: NotificationNotFound) {
        throw NotFoundException(missing.message)
    }

    @Operation(summary = "Отметить все свои уведомления прочитанными")
    @PostMapping("/read-all")
    fun readAll(request: HttpServletRequest): Map<String, Int> = mapOf(
        "updated" to service.markAllRead(CurrentCaller.claims(), request.getHeader(HttpHeaders.AUTHORIZATION)),
    )
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
        @field:NotBlank @field:Size(max = 255) val dedupKey: String,
        val email: NotificationEmail? = null,
    )
}

/** For credit. Not routed by the gateway (/internal is closed there). */
@Hidden
@RestController
@RequestMapping("/internal/v1/notifications")
class InternalNotificationController(private val service: NotificationUseCases) {
    @PostMapping
    fun deliver(@Valid @RequestBody request: InternalNotificationRequest): Map<String, Int> =
        mapOf(
            "stored" to service.deliver(
                request.items.map {
                    NewNotification(
                        Audience(it.recipientId, it.recipientCompanyId, it.recipientRole),
                        it.type,
                        it.title,
                        it.body,
                        it.link,
                        it.dedupKey,
                        it.email,
                    )
                },
            ),
        )
}
