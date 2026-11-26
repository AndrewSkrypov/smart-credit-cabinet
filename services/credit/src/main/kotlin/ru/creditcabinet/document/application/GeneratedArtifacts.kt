package ru.creditcabinet.document.application

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.application.application.ApplicationAccess
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.notifications.NotificationEmail
import ru.creditcabinet.common.notifications.NotificationEmailKind
import ru.creditcabinet.common.notifications.PlatformNotification
import ru.creditcabinet.common.notifications.PlatformNotifications
import ru.creditcabinet.document.domain.ArtifactGroup
import ru.creditcabinet.document.domain.ArtifactInput
import ru.creditcabinet.document.domain.ArtifactKind
import ru.creditcabinet.document.domain.ArtifactPhase
import ru.creditcabinet.document.domain.ArtifactRecord
import ru.creditcabinet.document.domain.ArtifactStatus
import ru.creditcabinet.document.domain.ArtifactStorage
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.artifactChecksum
import ru.creditcabinet.offer.Loans
import ru.creditcabinet.offer.OfferRepository
import ru.creditcabinet.offer.SigningSessions
import ru.creditcabinet.offer.application.ContractTexts
import ru.creditcabinet.product.domain.LoanMath
import ru.creditcabinet.product.domain.ProductCatalog
import java.io.IOException
import java.time.Clock
import java.util.UUID

class ArtifactUnavailableException : RuntimeException() {
    override val message: String = "Файл временно недоступен. Обновите список и повторите создание файла."
}

@Service
class GeneratedArtifacts(
    private val store: GeneratedArtifactStore,
    private val inputs: ArtifactInputs,
    private val applications: ApplicationAccess,
    private val repository: ApplicationRepository,
    private val offers: OfferRepository,
    private val loans: Loans,
    private val products: ProductCatalog,
    private val contracts: ContractTexts,
    private val signing: SigningSessions,
    private val files: FileStore,
    private val renderer: ArtifactRendering,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    private val notifications: PlatformNotifications,
) : GeneratedArtifactOperations, GeneratedArtifactJobs {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun list(applicationId: UUID): List<ArtifactRecord> = prepare(applications.get(applicationId))

    private fun prepare(application: LoanApplication): List<ArtifactRecord> {
        val offer = offers.current(application.id) ?: throw NotFoundException("Банк ещё не сделал предложение")
        val loan = loans.find(application.id)
        val product = products.version(offer.productCode, offer.productVersion)!!
        val phase = if (loan == null) ArtifactPhase.PREVIEW else ArtifactPhase.FINAL
        val snapshot = if (loan == null) null else contracts.find(offer.id)
        val input = ArtifactInput(
            applicationId = application.id,
            applicationNumber = application.number,
            offerId = offer.id,
            companyName = application.company.name ?: "ИНН ${application.company.inn}",
            inn = application.company.inn,
            productName = product.name,
            amount = loan?.amount ?: offer.amount,
            termMonths = loan?.termMonths ?: offer.termMonths,
            calculation = LoanMath.calculate(
                loan?.amount ?: offer.amount,
                loan?.rate ?: offer.rate,
                loan?.termMonths ?: offer.termMonths,
                loan?.revolving ?: product.revolving,
            ),
            phase = phase,
            contractText = if (loan == null) "" else snapshot?.text ?: contracts.legacySummary(application, offer, product),
            contractTextHash = snapshot?.sha256,
            legacySummary = loan != null && snapshot == null,
            signedAt = if (loan == null) null else signing.latest(application.id)?.takeIf { it.offerId == offer.id }?.signedAt,
            contractNumber = loan?.contractNumber,
            sourceCreatedAt = snapshot?.createdAt ?: offer.createdAt,
        )
        val frozen = inputs.freeze(input)
        val group = ArtifactGroup(application.id, offer.id, renderer.templateVersion, frozen.hash)
        val now = clock.instant()
        val artifacts = ArtifactKind.entries.filter { loan != null || it != ArtifactKind.CONTRACT_PDF }.map { kind ->
            val id = UUID.randomUUID()
            ArtifactRegistration(
                id,
                kind,
                renderer.title(kind, input),
                "application-${application.number}-${kind.name.lowercase()}.${kind.extension}",
                ArtifactStorage("generated-$id.${kind.extension}", files.writeBackend),
            )
        }
        return store.register(group, phase, frozen.json, artifacts, now)
    }

    /** Rendering and storage failures stay outside the loan disbursement transaction. */
    override fun prepareFinalLoans() {
        store.loansAwaitingFiles(renderer.templateVersion).forEach { id ->
            runCatching { tx.executeWithoutResult { prepare(repository.find(id) ?: throw NotFoundException("Заявка не найдена")) } }
                .onFailure { log.warn("Artifact preparation failed for {}: {}", id, it.javaClass.simpleName) }
        }
    }

    /** Claim and enqueue the notification in a single transaction. */
    override fun notifyReadyGroups(): Int =
        store.readyGroups(renderer.templateVersion).count { group ->
            tx.execute {
                if (!store.claimReadyNotice(group, clock.instant())) return@execute false
                val id = group.applicationId
                val application = repository.find(id) ?: throw NotFoundException("Заявка не найдена")
                notifications.send(
                    listOf(
                        PlatformNotification(
                            recipientCompanyId = application.company.id,
                            type = PlatformNotification.APPLICATION_STATUS,
                            title = "Документы по кредиту готовы",
                            body = "В кабинете доступны PDF сохранённых условий и график платежей в PDF и XLSX.",
                            link = "/client/applications/$id",
                            dedupKey = "generated-files:$id:${group.offerId}:${group.templateVersion}:${group.inputHash}",
                            email = NotificationEmail(NotificationEmailKind.FILES_READY),
                        ),
                    ),
                )
                true
            }!!
        }

    /** The row lock spans the bounded write, and retries reuse the same object name. */
    override fun generateOne(): Boolean = tx.execute {
        val artifact = store.lockNextArtifact(renderer.templateVersion, clock.instant()) ?: return@execute false
        val now = clock.instant()
        try {
            val input = inputs.restore(artifact.input)
            val bytes = renderer.render(artifact.kind, input)
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Generated file size is invalid" }
            files.put(artifact.storage.name, bytes.inputStream(), bytes.size.toLong(), artifact.storage.backend)
            store.markReady(artifact.id, bytes.size.toLong(), artifactChecksum(bytes), now)
        } catch (failure: Exception) {
            store.markGenerationFailed(artifact.id, now)
            log.warn("Artifact generation failed for {}: {}", artifact.id, failure.javaClass.simpleName)
        }
        true
    }!!

    @Transactional
    override fun retry(applicationId: UUID, artifactId: UUID): ArtifactRecord {
        applications.get(applicationId)
        val record = store.find(applicationId, artifactId, lock = true) ?: throw NotFoundException("Файл не найден")
        if (record.status == ArtifactStatus.READY) return record
        if (record.group.templateVersion != renderer.templateVersion) {
            // Copy the original frozen input, including preview files after disbursement.
            val replacement = store.createReplacement(record, UUID.randomUUID(), renderer.templateVersion, clock.instant())
            return retry(applicationId, replacement)
        }
        store.reset(artifactId, clock.instant())
        return record.copy(status = ArtifactStatus.PENDING)
    }

    override fun download(applicationId: UUID, artifactId: UUID): ArtifactDownload {
        applications.get(applicationId)
        val metadata = store.find(applicationId, artifactId) ?: throw NotFoundException("Файл не найден")
        if (metadata.status != ArtifactStatus.READY) throw ConflictException("Файл ещё не готов")
        try {
            val file = files.get(metadata.storage.name, metadata.storage.backend) ?: throw IOException("Generated file missing")
            val bytes = file.content.use { it.readNBytes(MAX_BYTES + 1) }
            require(bytes.size.toLong() == metadata.sizeBytes && bytes.size.toLong() == file.sizeBytes && artifactChecksum(bytes) == metadata.checksum) {
                "Generated file checksum mismatch"
            }
            return ArtifactDownload(metadata, bytes)
        } catch (failure: Exception) {
            store.markStorageUnavailable(artifactId, clock.instant())
            throw ArtifactUnavailableException()
        }
    }

    private companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
    }
}
