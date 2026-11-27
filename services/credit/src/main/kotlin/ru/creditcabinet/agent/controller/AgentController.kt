package ru.creditcabinet.agent.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.core.io.InputStreamResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import ru.creditcabinet.agent.application.AgentApplicationService
import ru.creditcabinet.agent.domain.ActionOwner
import ru.creditcabinet.application.controller.ApplicationController
import ru.creditcabinet.application.controller.ApplicationRequest
import ru.creditcabinet.application.controller.ApplicationResponse
import ru.creditcabinet.application.controller.ApplicationSummary
import ru.creditcabinet.application.controller.DocumentView
import ru.creditcabinet.application.controller.JournalEntryView
import ru.creditcabinet.application.controller.ProvideInfoRequest
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.chat.application.ChatService
import ru.creditcabinet.chat.controller.ChatView
import ru.creditcabinet.chat.controller.MessageRequest
import ru.creditcabinet.chat.controller.MessageView
import ru.creditcabinet.chat.controller.ReadRequest
import ru.creditcabinet.chat.domain.ChatFile
import ru.creditcabinet.chat.domain.ChatState
import ru.creditcabinet.chat.domain.ChatUpload
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.common.exception.ForbiddenException
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.preapproved.controller.PreApprovedOfferView
import ru.creditcabinet.product.domain.ProductCatalog
import java.nio.charset.StandardCharsets
import java.util.UUID

data class PreparedDraftResponse(
    val application: ApplicationResponse,
    val requiresHumanConfirmation: Boolean,
    val nextRequiredStep: String,
)

@Tag(name = "Агентский канал: заявки, портфель, документы и чат")
@RestController
@RequestMapping("/api/v1/agent")
class AgentController(
    private val service: AgentApplicationService,
    private val applicationController: ApplicationController,
    private val chat: ChatService,
    private val products: ProductCatalog,
) {
    @Operation(summary = "Сводка портфеля и комиссий агента")
    @GetMapping("/portfolio")
    fun portfolio(): AgentPortfolioResponse = AgentPortfolioResponse.of(service.portfolio())

    @Operation(summary = "Предодобренные предложения закреплённых компаний")
    @GetMapping("/pre-approved-offers")
    fun preApprovedOffers(): List<PreApprovedOfferView> {
        val catalog = products.all().associateBy { it.code }
        return service.listPreApprovedOffers().map { offer ->
            PreApprovedOfferView(
                id = offer.id,
                companyId = offer.companyId,
                productCode = offer.productCode,
                productName = catalog[offer.productCode]?.name ?: offer.productCode,
                maxAmount = offer.maxAmount,
                minTermMonths = offer.minTermMonths,
                maxTermMonths = offer.maxTermMonths,
                rate = offer.rate,
                validUntil = offer.validUntil,
                status = offer.status,
            )
        }
    }

    @Operation(summary = "Создать черновик заявки по предодобренному предложению закреплённой компании")
    @PostMapping("/pre-approved-offers/{id}/apply")
    @ResponseStatus(HttpStatus.CREATED)
    fun applyPreApprovedOffer(@PathVariable id: UUID): ApplicationResponse =
        detail(service.applyPreApprovedOffer(id))

    @Operation(
        summary = "Автоматическая подготовка черновика по предодобрению с обязательным подтверждением человеком (статус строго DRAFT)",
    )
    @PostMapping("/pre-approved-offers/{id}/prepare-draft")
    @ResponseStatus(HttpStatus.CREATED)
    fun prepareDraftFromOffer(@PathVariable id: UUID): PreparedDraftResponse {
        val view = applicationController.detailView(service.applyPreApprovedOffer(id))
        return PreparedDraftResponse(
            application = view,
            requiresHumanConfirmation = true,
            nextRequiredStep = "Проверьте параметры черновика и отправьте заявку на подтверждение клиенту: без согласия клиента заявка не передаётся в банк.",
        )
    }

    @Operation(summary = "Заявки агента по закреплённым компаниям")
    @GetMapping("/applications")
    fun listApplications(@RequestParam(required = false) status: Set<ApplicationStatus>?): List<ApplicationSummary> {
        val apps = service.listApplications(status)
        val unread = chat.unread(apps)
        return apps.map { application ->
            val action = service.action(application)
            applicationController.summaryView(application).copy(
                unreadChatCount = unread[application.id] ?: 0,
                actionOwner = action?.owner,
                actionDeadline = action?.deadline,
                actionLabel = action?.label,
            )
        }.sortedWith(
            compareBy<ApplicationSummary> { it.actionDeadline == null }
                .thenBy { it.actionDeadline }
                .thenBy { it.actionOwner != ActionOwner.AGENT },
        )
    }

    @Operation(summary = "Создать черновик заявки от имени агента для закреплённой компании")
    @PostMapping("/applications")
    @ResponseStatus(HttpStatus.CREATED)
    fun createApplication(@Valid @RequestBody request: AgentCreateApplicationRequest): ApplicationResponse =
        detail(service.create(request.companyId, request.toTerms()))

    @Operation(summary = "Карточка агентской заявки")
    @GetMapping("/applications/{id}")
    fun getApplication(@PathVariable id: UUID): ApplicationResponse =
        detail(service.get(id))

    @Operation(summary = "История своей агентской заявки")
    @GetMapping("/applications/{id}/events")
    fun events(@PathVariable id: UUID): List<JournalEntryView> =
        service.events(id).map { JournalEntryView.of(it, forStaff = false).copy(actorId = null, payload = emptyMap()) }

    @Operation(summary = "Проверить текущий доступ агента к уведомлению по своей заявке")
    @GetMapping("/applications/{id}/notification-access")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun notificationAccess(@PathVariable id: UUID) {
        service.get(id)
    }

    @Operation(summary = "Изменить черновик агентской заявки")
    @PutMapping("/applications/{id}")
    fun updateApplication(@PathVariable id: UUID, @Valid @RequestBody request: ApplicationRequest): ApplicationResponse =
        detail(service.updateDraft(id, request.toTerms()))

    @Operation(summary = "Отправить черновик клиенту на подтверждение и согласие БКИ (T22)")
    @PostMapping("/applications/{id}/request-confirmation")
    fun requestConfirmation(@PathVariable id: UUID): ApplicationResponse =
        detail(service.requestClientConfirmation(id))

    @Operation(summary = "Отменить агентскую заявку до отправки в банк (T25)")
    @PostMapping("/applications/{id}/cancel")
    fun cancelApplication(@PathVariable id: UUID): ApplicationResponse =
        detail(service.cancel(id))

    @Operation(summary = "Ответить на запрос банка от имени агента (T5)")
    @PostMapping("/applications/{id}/info-provided")
    fun provideInfo(@PathVariable id: UUID, @Valid @RequestBody(required = false) request: ProvideInfoRequest?): ApplicationResponse =
        detail(service.provideInfo(id, request?.answer?.trim()?.takeIf { it.isNotEmpty() }))

    @Operation(summary = "Список документов агентской заявки")
    @GetMapping("/applications/{id}/documents")
    fun listDocuments(@PathVariable id: UUID): List<DocumentView> =
        service.listDocuments(id).map(DocumentView::of)

    @Operation(summary = "Загрузить документ по агентской заявке")
    @PostMapping("/applications/{id}/documents", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun uploadDocument(
        @PathVariable id: UUID,
        @RequestParam type: DocumentType,
        @RequestParam(defaultValue = "false") privateToBank: Boolean,
        @RequestPart file: MultipartFile,
        @RequestHeader("Idempotency-Key", required = false) operationKey: String?,
    ): DocumentView {
        if (privateToBank) throw ForbiddenException("Приватную выписку банку может загрузить клиент")
        return DocumentView.of(service.uploadDocument(id, type, file, operationKey))
    }

    @Operation(summary = "Скачать документ агентской заявки")
    @GetMapping("/applications/{id}/documents/{documentId}")
    fun downloadDocument(@PathVariable id: UUID, @PathVariable documentId: UUID): ResponseEntity<InputStreamResource> {
        val (document, file) = service.openDocument(id, documentId)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(file.sizeBytes)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(document.fileName, StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(InputStreamResource(file.content))
    }

    @Operation(summary = "Удалить документ из черновика агентской заявки")
    @DeleteMapping("/applications/{id}/documents/{documentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteDocument(@PathVariable id: UUID, @PathVariable documentId: UUID) =
        service.deleteDocument(id, documentId)

    @Operation(summary = "Сообщения чата по агентской заявке")
    @GetMapping("/applications/{id}/messages")
    fun listMessages(
        @PathVariable id: UUID,
        @RequestParam(required = false) after: Long?,
        @RequestParam(required = false) before: Long?,
        @RequestParam(defaultValue = "50") limit: Int,
    ): ChatView {
        CurrentCaller.requireRole(Roles.AGENT)
        // ChatService calls ApplicationService.get(id), enforcing agentId + live mandate check once.
        return ChatView.of(chat.page(id, after, before, limit))
    }

    @Operation(summary = "Отправить сообщение в чат по агентской заявке")
    @PostMapping("/applications/{id}/messages")
    fun sendMessage(
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestBody request: MessageRequest,
    ): MessageView {
        CurrentCaller.requireRole(Roles.AGENT)
        return MessageView.of(chat.send(id, parseKey(key), request.text, request.documentIds))
    }

    @Operation(summary = "Отметить сообщения прочитанными по агентской заявке")
    @PostMapping("/applications/{id}/messages/read")
    fun readMessages(@PathVariable id: UUID, @RequestBody request: ReadRequest): ChatState {
        CurrentCaller.requireRole(Roles.AGENT)
        return chat.read(id, request.lastReadSeq)
    }

    @Operation(summary = "Загрузить вложение в чат по агентской заявке")
    @PostMapping("/applications/{id}/messages/attachments", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadMessageAttachment(
        @PathVariable id: UUID,
        @RequestHeader("Idempotency-Key") key: String,
        @RequestPart file: MultipartFile,
    ): ChatFile {
        CurrentCaller.requireRole(Roles.AGENT)
        return chat.upload(id, parseKey(key), ChatUpload(file.originalFilename ?: "document", file.bytes))
    }

    private fun detail(application: LoanApplication): ApplicationResponse {
        val action = service.action(application)
        return applicationController.detailView(application).copy(
            actionOwner = action?.owner,
            actionDeadline = action?.deadline,
            actionLabel = action?.label,
        )
    }

    private fun parseKey(value: String): UUID = try {
        UUID.fromString(value)
    } catch (_: IllegalArgumentException) {
        throw BadRequestException("Укажите UUID в заголовке Idempotency-Key")
    }
}
