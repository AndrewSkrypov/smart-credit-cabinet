package ru.creditcabinet.application.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.core.io.InputStreamResource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
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
import ru.creditcabinet.application.domain.ActorType
import ru.creditcabinet.application.domain.ApplicationEvent
import ru.creditcabinet.application.domain.ApplicationStateMachine
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.InfoRequests
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.service.ApplicationService
import ru.creditcabinet.application.service.toActor
import ru.creditcabinet.assessment.domain.DecisionRepository
import ru.creditcabinet.chat.application.ChatService
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.security.CurrentCaller
import ru.creditcabinet.document.DocumentService
import ru.creditcabinet.document.domain.DocumentRequirements
import ru.creditcabinet.document.domain.DocumentType
import ru.creditcabinet.offer.ContractTextView
import ru.creditcabinet.offer.Loans
import ru.creditcabinet.offer.OfferKind
import ru.creditcabinet.offer.OfferService
import ru.creditcabinet.offer.SigningSessions
import ru.creditcabinet.product.domain.Calculation
import ru.creditcabinet.product.domain.LoanMath
import java.nio.charset.StandardCharsets
import java.util.UUID

@Tag(name = "Заявки, документы и выдача")
@RestController
@RequestMapping("/api/v1/applications")
class ApplicationController(
    private val service: ApplicationService,
    private val documents: DocumentService,
    private val offers: OfferService,
    private val infoRequests: InfoRequests,
    private val decisions: DecisionRepository,
    private val signing: SigningSessions,
    private val loans: Loans,
    private val chat: ChatService,
) {
    @Operation(summary = "Заявки: клиенту — его компании, сотрудникам банка — очередь без черновиков")
    @GetMapping
    fun list(@RequestParam(required = false) status: Set<ApplicationStatus>?): List<ApplicationSummary> {
        val applications = service.list(status)
        val unread = chat.unread(applications)
        return applications.map { summary(it).copy(unreadChatCount = unread[it.id] ?: 0) }
    }

    @Operation(summary = "Создать черновик заявки")
    @PostMapping
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    fun create(@Valid @RequestBody request: ApplicationRequest): ApplicationResponse = detail(service.create(request.toTerms()))

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
    fun schedule(@PathVariable id: UUID): Calculation {
        val application = service.get(id)
        val loan = loans.find(application.id)
        val offer = offers.current(application.id) ?: throw NotFoundException("Графика пока нет: банк ещё не сделал предложение")
        val product = service.product(application, offer.productCode)
        return LoanMath.calculate(loan?.amount ?: offer.amount, loan?.rate ?: offer.rate, loan?.termMonths ?: offer.termMonths, product.revolving)
    }

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
    fun upload(@PathVariable id: UUID, @RequestParam type: DocumentType, @RequestPart file: MultipartFile): DocumentView =
        DocumentView.of(documents.upload(id, type, file))

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
        val offer = offers.current(application.id)
        // What changed against the request: the rules' notes for an automatic counter offer, the underwriter's comment for theirs.
        val offerDecision = offer?.let { decisions.find(it.decisionId) }
        val changes = when {
            offer?.kind != OfferKind.COUNTER || offerDecision == null -> emptyList()
            offerDecision.decidedByType == ActorType.SYSTEM -> offerDecision.recommendation?.notes.orEmpty()
            else -> listOfNotNull(offerDecision.comment)
        }
        val decision = decisions.latest(application.id)
        return ProcessView(
            infoRequest = infoRequests.open(application.id)?.let(InfoRequestView::of),
            offer = offer?.let {
                OfferView.of(
                    it,
                    service.product(application, it.productCode).name,
                    if (decision?.decidedByType ==
                        ActorType.SYSTEM
                    ) {
                        changes
                    } else {
                        listOfNotNull(decision?.comment)
                    },
                )
            },
            signing = signing.latest(application.id)?.toView(showCode = isOwnClient && CurrentCaller.claims().canSign),
            loan = loans.find(application.id)?.let(LoanView::of),
            outcome = decision?.takeIf { application.status == ApplicationStatus.REJECTED }?.let(OutcomeView::of),
        )
    }

    private fun summary(application: LoanApplication): ApplicationSummary {
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
        )
    }

    private fun detail(application: LoanApplication): ApplicationResponse {
        val claims = CurrentCaller.claims()
        val product = service.product(application)
        val terms = application.terms
        val uploaded = documents.list(application.id)
        val isOwnClient = claims.isClient && claims.companyId == application.company.id
        val actions = claims.toActor()?.let { actor ->
            ApplicationStateMachine.eventsFrom(application.status, actor.type)
                // Only a person with the right to sign may sign for the company.
                .filter { it != ApplicationEvent.SIGN || claims.canSign }
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
            currentProduct = if (application.status == ApplicationStatus.DRAFT) service.currentProduct(product.code) else null,
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
            editable = isOwnClient && application.status == ApplicationStatus.DRAFT,
            canUploadDocuments = isOwnClient && application.status in setOf(ApplicationStatus.DRAFT, ApplicationStatus.INFO_REQUESTED),
            submissionProblems = if (application.status == ApplicationStatus.DRAFT) {
                val current = service.currentProduct(product.code)
                if (current?.active != true) listOf("Продукт выключен из каталога — выберите другой продукт") else application.submissionProblems(current)
            } else {
                emptyList()
            },
            requiredDocuments = DocumentRequirements.required(product, terms.amount, application.company.legalForm).map { requirement ->
                RequiredDocumentView(requirement.type, requirement.type.title, requirement.reason, uploaded.any { it.type == requirement.type })
            },
            documents = uploaded.map(DocumentView::of),
            estimate = estimate,
            process = process(application, isOwnClient),
        )
    }
}
