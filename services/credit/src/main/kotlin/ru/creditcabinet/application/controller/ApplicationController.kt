package ru.creditcabinet.application.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
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
import ru.creditcabinet.application.application.ApplicationPages
import ru.creditcabinet.application.application.ApplicationProcesses
import ru.creditcabinet.application.domain.ApplicationEvent
import ru.creditcabinet.application.domain.ApplicationStateMachine
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.service.ApplicationService
import ru.creditcabinet.application.service.toActor
import ru.creditcabinet.chat.application.ChatService
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.document.application.ApplicationDocuments
import ru.creditcabinet.document.controller.toDocumentUpload
import ru.creditcabinet.document.domain.DocumentRequirements
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.offer.ContractTextView
import ru.creditcabinet.offer.application.OfferActions
import ru.creditcabinet.product.domain.Calculation
import ru.creditcabinet.product.domain.LoanMath
import java.nio.charset.StandardCharsets
import java.util.UUID

@Tag(name = "Заявки, документы и выдача")
@RestController
@RequestMapping("/api/v1/applications")
class ApplicationController(
    private val service: ApplicationService,
    private val documents: ApplicationDocuments,
    private val offers: OfferActions,
    private val processes: ApplicationProcesses,
    private val chat: ChatService,
    private val pages: ApplicationPages,
) {
    @Operation(summary = "Заявки: клиенту — его компании, сотрудникам банка — очередь без черновиков")
    @GetMapping
    fun list(@RequestParam(required = false) status: Set<ApplicationStatus>?): List<ApplicationSummary> {
        val applications = service.list(status)
        val unread = chat.unread(applications)
        return applications.map { summary(it).copy(unreadChatCount = unread[it.id] ?: 0) }
    }

    @Operation(summary = "Ограниченная SQL-страница заявок; счётчики по поиску, клиентское превью действий глобально")
    @GetMapping("/page")
    fun page(
        @RequestParam(defaultValue = "0") page: Long,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(required = false) group: String?,
        @RequestParam(defaultValue = "") q: String,
        @RequestParam(defaultValue = "default") sort: String,
    ): ApplicationPageResponse {
        val result = pages.read(page, size, group, q, sort)
        val unread = chat.unread((result.items + result.actionRequiredPreview).distinctBy { it.id })
        fun view(application: LoanApplication) = summary(application).copy(unreadChatCount = unread[application.id] ?: 0)
        return ApplicationPageResponse(
            result.items.map(::view), result.page, result.size, result.totalElements, result.totalPages,
            result.hasNext, result.countsByGroup, result.actionRequiredCount, result.actionRequiredPreview.map(::view),
        )
    }

    @Operation(summary = "Создать черновик заявки")
    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: ApplicationRequest): ApplicationResponse = detail(service.create(request.toTerms()), newDraft = true)

    @Operation(summary = "Карточка своей заявки с состоянием конвейера, решением и документами")
    @GetMapping("/{id}")
    fun get(@PathVariable id: UUID): ApplicationResponse = detail(service.get(id))

    @Operation(summary = "Изменить черновик")
    @PutMapping("/{id}")
    fun update(@PathVariable id: UUID, @Valid @RequestBody request: ApplicationRequest): ApplicationResponse =
        detail(service.updateDraft(id, request.toTerms()))

    @Operation(summary = "Отправить заявку в банк (T2)")
    @PostMapping("/{id}/submit")
    fun submit(
        @PathVariable id: UUID,
        @Parameter(description = "Повтор с тем же ключом вернёт результат первого запроса")
        @RequestHeader("Idempotency-Key", required = false) idempotencyKey: String?,
        @RequestBody(required = false) request: SubmitRequest?,
    ): ApplicationResponse = detail(service.submit(id, idempotencyKey?.trim()?.takeIf { it.isNotEmpty() }?.take(128), request?.acceptedProductVersion))

    @Operation(summary = "Подтвердить агентскую заявку, дать согласие на БКИ и отправить в банк (T23)")
    @PostMapping("/{id}/confirm")
    fun confirm(@PathVariable id: UUID, @RequestBody request: ConfirmApplicationRequest): ApplicationResponse =
        detail(service.confirmByClient(id, request.bureauConsent, request.acceptedProductVersion, request.acceptedScenarioRevision))

    @Operation(summary = "Вернуть агентскую заявку агенту на доработку (T24)")
    @PostMapping("/{id}/return-to-agent")
    fun returnToAgent(@PathVariable id: UUID, @Valid @RequestBody(required = false) request: ReturnToAgentRequest?): ApplicationResponse =
        detail(service.returnToAgentByClient(id, request?.comment))

    @Operation(summary = "Отозвать заявку (T21)")
    @PostMapping("/{id}/withdraw")
    fun withdraw(@PathVariable id: UUID, @Valid @RequestBody(required = false) request: WithdrawRequest?): ApplicationResponse =
        detail(service.withdraw(id, request?.comment))

    @Operation(summary = "Ответить на запрос банка: документы загружены, на вопросы дан ответ (T5)")
    @PostMapping("/{id}/info-provided")
    fun provideInfo(@PathVariable id: UUID, @Valid @RequestBody(required = false) request: ProvideInfoRequest?): ApplicationResponse =
        detail(service.provideInfo(id, request?.answer?.trim()?.takeIf { it.isNotEmpty() }))

    @Operation(summary = "Принять предложение банка (T16); сразу выпускается код подписи")
    @PostMapping("/{id}/offer/accept")
    fun acceptOffer(@PathVariable id: UUID): ApplicationResponse = detail(offers.accept(id))

    @Operation(summary = "Отказаться от предложения банка (T17)")
    @PostMapping("/{id}/offer/decline")
    fun declineOffer(@PathVariable id: UUID, @Valid @RequestBody(required = false) request: DeclineRequest?): ApplicationResponse =
        detail(offers.decline(id, request?.comment))

    @Operation(summary = "Получить новый код подписи")
    @PostMapping("/{id}/signing/code")
    fun signingCode(@PathVariable id: UUID): ApplicationResponse {
        offers.requestCode(id)
        return detail(service.get(id))
    }

    @Operation(summary = "Подписать договор кодом (T18)")
    @PostMapping("/{id}/sign")
    fun sign(@PathVariable id: UUID, @Valid @RequestBody request: SignRequest): ApplicationResponse = detail(offers.sign(id, request.code))

    @Operation(summary = "График платежей по выданному кредиту или по предложению банка")
    @GetMapping("/{id}/schedule")
    fun schedule(@PathVariable id: UUID): Calculation = processes.schedule(service.get(id))

    @Operation(summary = "Подписываемый текст договора и сохранённый снимок; для старых заявок — сводка условий")
    @GetMapping("/{id}/contract")
    fun contract(@PathVariable id: UUID): ContractTextView = offers.contract(id)

    @Operation(summary = "Журнал заявки: история для клиента, аудит для банка")
    @GetMapping("/{id}/events")
    fun events(@PathVariable id: UUID): List<JournalEntryView> {
        val forStaff = CurrentCaller.claims().isBankStaff
        return service.journal(id).map { JournalEntryView.of(it, forStaff) }
    }

    @Operation(summary = "Загрузить документ (в черновик или по запросу банка)")
    @PostMapping("/{id}/documents", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    fun upload(
        @PathVariable id: UUID,
        @RequestParam type: DocumentType,
        @RequestParam(defaultValue = "false") privateToBank: Boolean,
        @RequestPart file: MultipartFile,
        @RequestHeader("Idempotency-Key", required = false) operationKey: String?,
    ): DocumentView = DocumentView.of(documents.upload(id, type, file.toDocumentUpload().copy(operationKey = operationKey), privateToBank))

    @Operation(summary = "Удалить свой документ из редактируемого черновика")
    @DeleteMapping("/{id}/documents/{documentId}")
    @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    fun deleteDocument(@PathVariable id: UUID, @PathVariable documentId: UUID) = documents.delete(id, documentId)

    @Operation(summary = "Скачать документ: представителям компании и сотрудникам банка")
    @GetMapping("/{id}/documents/{documentId}")
    fun download(@PathVariable id: UUID, @PathVariable documentId: UUID): ResponseEntity<InputStreamResource> {
        val (document, file) = documents.open(id, documentId)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(file.sizeBytes)
            // Always a download, never rendered by the browser.
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(document.fileName, StandardCharsets.UTF_8).build().toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(InputStreamResource(file.content))
    }

    private fun process(application: LoanApplication, isOwnClient: Boolean): ProcessView {
        val process = processes.read(application)
        return ProcessView(
            infoRequest = process.infoRequest?.let { InfoRequestView.of(it, documents.satisfiedDocumentTypes(it)) },
            offer = process.offer?.let { OfferView.of(it.offer, it.productName, it.changes) },
            signing = process.signing?.toView(showCode = isOwnClient && CurrentCaller.claims().canSign),
            loan = process.loan?.let(LoanView::of),
            outcome = process.rejection?.let(OutcomeView::of),
        )
    }

    internal fun summaryView(application: LoanApplication): ApplicationSummary = summary(application)

    internal fun detailView(application: LoanApplication): ApplicationResponse = detail(application)

    private fun summary(original: LoanApplication): ApplicationSummary {
        val application = if (CurrentCaller.claims().isClient) service.clientReadView(original) else original
        val product = service.product(application)
        return ApplicationSummary(
            id = application.id,
            number = application.number,
            status = application.status,
            companyInn = application.company.inn,
            companyName = application.company.name,
            productCode = product.code,
            productName = product.name,
            amount = application.terms.amount,
            termMonths = application.terms.termMonths,
            createdAt = application.createdAt,
            submittedAt = application.submittedAt,
            statusChangedAt = application.statusChangedAt,
            agentId = application.agentId,
            agentName = application.agentName,
            agentCommissionAmount = application.agentCommissionAmount,
            agentCommissionStatus = application.agentCommissionStatus,
        )
    }

    private fun detail(original: LoanApplication, newDraft: Boolean = false): ApplicationResponse {
        val claims = CurrentCaller.claims()
        val application = if (claims.isClient) service.clientReadView(original) else original
        val product = service.product(application)
        val catalogPreview = if (application.status == ApplicationStatus.DRAFT || application.status == ApplicationStatus.PENDING_CLIENT_CONFIRMATION) {
            try {
                Result.success(service.currentProduct(product.code))
            } catch (failure: ApiException) {
                if (failure.httpStatus != HttpStatus.SERVICE_UNAVAILABLE) throw failure
                Result.failure(failure)
            }
        } else {
            Result.success(null)
        }
        val currentProduct = catalogPreview.getOrNull()
        val terms = application.terms
        val uploaded = if (newDraft) emptyList() else documents.list(application)
        val isOwnClient = claims.isClient && claims.companyId == application.company.id
        val isOwnAgent = claims.isAgent && application.activeAgentId == claims.userId
        val canResume = isOwnClient &&
            try {
                service.canResumeIndependently(application)
            } catch (_: ApiException) {
                false
            }
        val actions = claims.toActor()?.let { actor ->
            ApplicationStateMachine.eventsFrom(application.status, actor.type)
                // Only a person with the right to sign may sign for the company.
                .filter { it != ApplicationEvent.SIGN || claims.canSign }
                // Clients confirm agent applications via CONFIRM_AND_SUBMIT, never direct SUBMIT.
                .filter { !(it == ApplicationEvent.SUBMIT && application.agentId != null) }
                .filter { it != ApplicationEvent.CONFIRM_AND_SUBMIT || application.status != ApplicationStatus.DRAFT || canResume }
                .filter { it != ApplicationEvent.WITHDRAW || application.status != ApplicationStatus.DRAFT || application.activeAgentId == null }
        }.orEmpty()
        val estimate = if (product.amountFits(terms.amount) && product.termFits(terms.termMonths)) {
            LoanMath.calculate(terms.amount, product.baseRate, terms.termMonths, product.revolving).let { EstimateView(it.rate, it.monthlyPayment) }
        } else {
            null
        }
        return ApplicationResponse(
            id = application.id,
            number = application.number,
            status = application.status,
            closeReason = application.closeReason,
            company = with(application.company) { CompanyView(id, inn, name, legalForm) },
            product = ProductView(product.code, product.name, product.revolving, product.version),
            currentProduct = currentProduct,
            purpose = terms.purpose,
            purposeTitle = terms.purpose.title,
            purposeComment = terms.purposeComment,
            amount = terms.amount,
            termMonths = terms.termMonths,
            declaredRevenue12m = terms.declaredRevenue12m,
            declaredMonthlyDebtPayments = terms.declaredMonthlyDebtPayments,
            bureauConsent = terms.bureauConsent,
            createdAt = application.createdAt,
            submittedAt = application.submittedAt,
            statusChangedAt = application.statusChangedAt,
            version = application.version,
            allowedActions = actions,
            editable = ((isOwnClient && application.agentId == null) || isOwnAgent) && application.status == ApplicationStatus.DRAFT,
            canUploadDocuments = when (application.status) {
                ApplicationStatus.DRAFT -> (isOwnClient && application.activeAgentId == null) || isOwnAgent
                ApplicationStatus.INFO_REQUESTED -> isOwnClient || isOwnAgent
                else -> false
            },
            submissionProblems = if (application.status == ApplicationStatus.DRAFT) {
                when {
                    catalogPreview.isFailure -> listOf("Каталог временно недоступен — попробуйте позже")
                    currentProduct?.active != true -> listOf("Продукт выключен из каталога — выберите другой продукт")
                    application.agentId != null -> application.agentSubmissionProblems(currentProduct)
                    else -> application.submissionProblems(currentProduct)
                }
            } else {
                emptyList()
            },
            requiredDocuments = DocumentRequirements.required(product, terms.amount, application.company.legalForm).map { requirement ->
                RequiredDocumentView(requirement.type, requirement.type.title, requirement.reason, uploaded.any { it.type == requirement.type })
            },
            documents = uploaded.map(DocumentView::of),
            estimate = estimate,
            process = process(application, isOwnClient),
            agentId = application.agentId,
            agentName = application.agentName,
            preApprovedOfferId = application.preApprovedOfferId,
            agentCommissionRateBps = application.agentCommissionRateBps,
            agentCommissionAmount = application.agentCommissionAmount,
            agentCommissionStatus = application.agentCommissionStatus,
            canResumeIndependently = canResume,
            clientManaged = application.clientManaged,
        )
    }
}

data class ApplicationPageResponse(
    val items: List<ApplicationSummary>,
    val page: Int,
    val size: Int,
    val totalElements: Long,
    val totalPages: Int,
    val hasNext: Boolean,
    val countsByGroup: Map<String, Long>,
    val actionRequiredCount: Long,
    val actionRequiredPreview: List<ApplicationSummary>,
)
