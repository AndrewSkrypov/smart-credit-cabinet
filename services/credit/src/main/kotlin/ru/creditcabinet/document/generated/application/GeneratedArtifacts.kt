package ru.creditcabinet.document.generated.application

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.application.application.ApplicationAccess
import ru.creditcabinet.application.application.CreditTransactions
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.notifications.NotificationEmail
import ru.creditcabinet.common.notifications.NotificationEmailKind
import ru.creditcabinet.common.notifications.PlatformNotification
import ru.creditcabinet.common.notifications.PlatformNotifications
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.generated.ArtifactInput
import ru.creditcabinet.document.generated.ArtifactKind
import ru.creditcabinet.document.generated.ArtifactPhase
import ru.creditcabinet.documents.ArtifactRecord
import ru.creditcabinet.documents.DocumentScope
import ru.creditcabinet.offer.Loans
import ru.creditcabinet.offer.OfferRepository
import ru.creditcabinet.offer.SigningSessions
import ru.creditcabinet.offer.application.ContractTexts
import ru.creditcabinet.product.domain.LoanMath
import ru.creditcabinet.product.domain.ProductCatalog
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class ArtifactStatus { PENDING, READY, ERROR }

data class ArtifactView(
    val id: UUID,
    val kind: ArtifactKind,
    val phase: ArtifactPhase,
    val title: String,
    val fileName: String,
    val contentType: String,
    val status: ArtifactStatus,
    val sizeBytes: Long?,
    val checksum: String?,
    val createdAt: Instant,
    val readyAt: Instant?,
    val errorMessage: String?,
    val downloadUrl: String?,
)

data class ArtifactDownload(val metadata: ArtifactView, val bytes: ByteArray)

@Service
class GeneratedArtifacts(
    private val records: GeneratedArtifactStore,
    private val codec: ArtifactInputCodec,
    private val applications: ApplicationAccess,
    private val repository: ApplicationRepository,
    private val offers: OfferRepository,
    private val loans: Loans,
    private val products: ProductCatalog,
    private val contracts: ContractTexts,
    private val signing: SigningSessions,
    private val files: FileStore,
    private val renderer: GeneratedArtifactRendering,
    private val tx: CreditTransactions,
    private val clock: Clock,
    private val notifications: PlatformNotifications,
    private val boundary: GeneratedArtifactRemote,
    private val failures: GeneratedArtifactFailures,
) {
    fun list(applicationId: UUID): List<ArtifactView> = if (boundary.remote) {
        prepare(applications.get(applicationId))
    } else {
        tx.execute { prepare(applications.get(applicationId)) }
    }

    private fun prepare(application: LoanApplication): List<ArtifactView> {
        val frozen = records.frozenFinal(application.id)
        if (frozen != null) return if (boundary.remote) prepareRemote(application, frozen) else prepareLocal(application, frozen)
        val offer = offers.current(application.id) ?: throw NotFoundException("Банк ещё не сделал предложение")
        val loan = loans.find(application.id)
        val product = products.version(offer.productCode, offer.productVersion)!!
        val phase = if (loan == null) ArtifactPhase.PREVIEW else ArtifactPhase.FINAL
        val snapshot = if (loan == null) null else contracts.find(offer.id)
        val input = ArtifactInput(
            application.id, application.number, offer.id, application.company.name ?: "ИНН ${application.company.inn}", application.company.inn, product.name,
            loan?.amount ?: offer.amount, loan?.termMonths ?: offer.termMonths,
            LoanMath.calculate(
                loan?.amount ?: offer.amount,
                loan?.rate ?: offer.rate,
                loan?.termMonths ?: offer.termMonths,
                loan?.revolving ?: product.revolving,
            ),
            phase, if (loan == null) "" else snapshot?.text ?: contracts.legacySummary(application, offer, product), snapshot?.sha256,
            loan != null && snapshot == null, if (loan == null) null else signing.latest(application.id)?.takeIf { it.offerId == offer.id }?.signedAt,
            loan?.contractNumber, snapshot?.createdAt ?: offer.createdAt,
        )
        if (boundary.remote) return prepareRemote(application, input)
        return prepareLocal(application, input)
    }

    private fun prepareLocal(application: LoanApplication, input: ArtifactInput): List<ArtifactView> {
        val inputJson = codec.encode(input)
        val hash = checksum(inputJson.toByteArray(Charsets.UTF_8))
        val now = clock.instant()
        val key = ArtifactInputKey(application.id, input.offerId, input.phase, hash)
        val backend = records.requestedBackend(key) ?: files.writeBackend
        val scope = DocumentScope(application.company.id, application.id)
        for (kind in kinds(input.phase)) {
            val id = UUID.randomUUID()
            records.putLocal(record(scope, id, application.number, input, kind, inputJson, hash, backend, now), now)
        }
        return records.listLocal(key, renderer.templateVersion)
    }

    private fun prepareRemote(application: LoanApplication, input: ArtifactInput): List<ArtifactView> {
        val canonical = codec.encode(input)
        val hash = checksum(canonical.toByteArray())
        // REQUIRED participates in a signing/business transaction without self-deadlocking
        // on its application FK. Only the durable publisher may send after that outer commit.
        val outerTransaction = tx.active
        val frozen = tx.execute {
            // Credit owns the immutable terms and durable command intent, never remote file metadata.
            records.freeze(ArtifactInputKey(application.id, input.offerId, input.phase, hash), canonical, files.writeBackend)
        }
        val stableInput = codec.decode(frozen)
        val stableJson = codec.encode(stableInput)
        val stableHash = checksum(stableJson.toByteArray())
        val key = ArtifactInputKey(application.id, stableInput.offerId, stableInput.phase, stableHash)
        val backend = checkNotNull(records.requestedBackend(key))
        val staged = remoteRecords(application, stableInput, stableJson, stableHash, backend)
        if (outerTransaction) return staged.map { it.remoteView() }
        return publishRemote(key, staged)
    }

    private fun kinds(phase: ArtifactPhase): List<ArtifactKind> =
        ArtifactKind.entries.filter { phase == ArtifactPhase.FINAL || it != ArtifactKind.CONTRACT_PDF }

    private fun record(
        scope: DocumentScope,
        id: UUID,
        number: Long?,
        input: ArtifactInput,
        kind: ArtifactKind,
        json: String,
        hash: String,
        backend: StorageBackend,
        createdAt: Instant,
    ): ArtifactRecord = ArtifactRecord(
        id, scope, input.offerId, kind.name, input.phase.name, renderer.templateVersion,
        hash, json, renderer.title(kind, input),
        "application-$number-${kind.name.lowercase()}.${kind.extension}", kind.contentType,
        "generated-$id.${kind.extension}", backend.name, createdAt,
    )

    private fun remoteRecords(
        application: LoanApplication,
        input: ArtifactInput,
        json: String,
        hash: String,
        backend: StorageBackend,
    ): List<ArtifactRecord> {
        val scope = boundary.scope(application.id)
        return kinds(input.phase).map { kind ->
            val id = UUID.nameUUIDFromBytes(
                "credit-artifact-v1:${application.id}:${input.offerId}:${kind.name}:${input.phase}:${renderer.templateVersion}:$hash".toByteArray(),
            )
            record(scope, id, application.number, input, kind, json, hash, backend, clock.instant())
        }
    }

    private fun publishRemote(key: ArtifactInputKey, staged: List<ArtifactRecord>): List<ArtifactView> {
        check(!tx.active)
        val result = boundary.prepare(staged)
        tx.executeWithoutResult { records.markDelivered(key) }
        return result
    }

    private fun publishPendingRemote() {
        records.pendingInputs().forEach { (id, input, backend) ->
            runCatching {
                val application = repository.find(id) ?: throw NotFoundException("Заявка не найдена")
                val json = codec.encode(input)
                val hash = checksum(json.toByteArray())
                val key = ArtifactInputKey(id, input.offerId, input.phase, hash)
                publishRemote(key, remoteRecords(application, input, json, hash, backend))
            }
        }
    }

    private fun confirmed(record: ArtifactRecord): Boolean {
        val phase = ArtifactPhase.entries.firstOrNull { it.name == record.phase } ?: return false
        return records.isReferenced(ArtifactInputKey(record.scope.applicationId, record.offerId, phase, record.inputHash))
    }

    /** Independent from issuing money: an unavailable renderer/store cannot roll back disbursement. */
    fun prepareFinalLoans() {
        if (boundary.remote) {
            records.loansWithoutRemoteArtifacts().forEach { id ->
                runCatching { prepare(repository.find(id) ?: throw NotFoundException("Заявка не найдена")) }
            }
            publishPendingRemote()
            return
        }
        records.loansWithoutLocalArtifacts(renderer.templateVersion).forEach { id ->
            runCatching { tx.executeWithoutResult { prepare(repository.find(id) ?: throw NotFoundException("Заявка не найдена")) } }
                .onFailure { failures.preparation(id, it) }
        }
    }

    /** All files must be committed READY; claim and outbox write are one transaction. */
    fun notifyReadyGroups(): Int {
        if (boundary.remote) return notifyRemoteGroups()
        return records.readyLocalGroups(renderer.templateVersion).count { key ->
            tx.execute {
                val statuses = records.lockGroupStatuses(key, renderer.templateVersion)
                if (statuses.size != 3 || statuses.any { it != ArtifactStatus.READY }) return@execute false
                claimAndNotify(key)
            }
        }
    }

    private fun notifyRemoteGroups(): Int = records.readyRemoteApplications(renderer.templateVersion).count { id ->
        val groups = boundary.list(boundary.scope(id)).filter {
            confirmed(it) && it.phase == "FINAL" && it.templateVersion == renderer.templateVersion
        }.groupBy { it.offerId to it.inputHash }
        groups.any { (group, artifacts) ->
            if (artifacts.size != 3 || artifacts.any { it.status != "READY" }) return@any false
            val key = ArtifactInputKey(id, group.first, ArtifactPhase.FINAL, group.second)
            if (!records.isDelivered(key)) return@any false
            tx.execute { claimAndNotify(key) }
        }
    }

    private fun claimAndNotify(key: ArtifactInputKey): Boolean {
        if (!records.claimNotice(key, renderer.templateVersion, clock.instant())) return false
        val id = key.applicationId
        val application = repository.find(id) ?: throw NotFoundException("Заявка не найдена")
        notifications.send(
            listOf(
                PlatformNotification(
                    recipientCompanyId = application.company.id,
                    type = PlatformNotification.APPLICATION_STATUS,
                    title = "Документы по кредиту готовы",
                    body = "В кабинете доступны PDF сохранённых условий и график платежей в PDF и XLSX.",
                    link = "/client/applications/$id",
                    dedupKey = "generated-files:$id:${key.offerId}:${renderer.templateVersion}:${key.hash}",
                    email = NotificationEmail(NotificationEmailKind.FILES_READY),
                ),
            ),
        )
        return true
    }

    /** Row lock spans the bounded write; competing workers skip it, retries keep the same object name. */
    fun generateOne(): Boolean {
        if (boundary.remote) return false
        return tx.execute {
            val work = records.lockNext(renderer.templateVersion, clock.instant()) ?: return@execute false
            val now = clock.instant()
            try {
                val input = codec.decode(work.inputJson)
                require(checksum(codec.encodeBytes(input)) == work.inputHash) { "Generated input checksum mismatch" }
                val bytes = renderer.render(work.kind, input)
                require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Generated file size is invalid" }
                files.put(work.file.storedName, bytes.inputStream(), bytes.size.toLong(), work.file.backend)
                records.markReady(work.id, bytes.size.toLong(), checksum(bytes), now)
            } catch (failure: Exception) {
                records.markFailed(work.id, now.plusSeconds(15), now)
                failures.generation(work.id, failure)
            }
            true
        }
    }

    @Transactional
    fun retry(applicationId: UUID, artifactId: UUID): ArtifactView {
        applications.get(applicationId)
        if (boundary.remote) {
            val scope = boundary.scope(applicationId)
            val record = boundary.list(scope).firstOrNull { it.id == artifactId && confirmed(it) } ?: throw NotFoundException("Файл не найден")
            return boundary.retry(scope, record.id)
        }
        val record = records.findLocal(applicationId, artifactId, lock = true) ?: throw NotFoundException("Файл не найден")
        if (record.status == ArtifactStatus.READY) return record
        val version = records.templateVersion(artifactId)
        if (version == renderer.templateVersion && (record.readyAt != null || record.checksum != null)) {
            throw ConflictException("Опубликованный файл требует восстановления исходного объекта")
        }
        if (version != renderer.templateVersion) {
            // Copy the old frozen input, including PREVIEW files after disbursement. Old READY links
            // stay immutable and the response identifies the current replacement file.
            val replacementId = UUID.randomUUID()
            val target = records.replaceLegacy(
                artifactId,
                replacementId,
                "generated-$replacementId.${record.kind.extension}",
                renderer.templateVersion,
                clock.instant(),
            )
            return retry(applicationId, target)
        }
        records.reset(artifactId, clock.instant(), clock.instant())
        return record.copy(status = ArtifactStatus.PENDING, errorMessage = null, downloadUrl = null)
    }

    fun download(applicationId: UUID, artifactId: UUID): ArtifactDownload {
        applications.get(applicationId)
        if (boundary.remote) {
            val scope = boundary.scope(applicationId)
            val record = boundary.list(scope).firstOrNull {
                it.id == artifactId && confirmed(it)
            } ?: throw NotFoundException("Файл не найден")
            if (record.status != "READY") throw ConflictException("Файл ещё не готов")
            return boundary.read(scope, record)
        }
        val metadata = records.findLocal(applicationId, artifactId) ?: throw NotFoundException("Файл не найден")
        if (metadata.status != ArtifactStatus.READY) throw ConflictException("Файл ещё не готов")
        val storage = records.file(artifactId)
        try {
            val file = files.get(storage.storedName, storage.backend) ?: throw java.io.IOException("Generated file missing")
            val bytes = file.content.use { it.readNBytes(MAX_BYTES + 1) }
            require(bytes.size.toLong() == metadata.sizeBytes && bytes.size.toLong() == file.sizeBytes && checksum(bytes) == metadata.checksum) {
                "Generated file checksum mismatch"
            }
            return ArtifactDownload(metadata, bytes)
        } catch (_: Exception) {
            // A read failure cannot revoke publication or authorize overwriting its immutable ID.
            throw GeneratedOriginalUnavailable()
        }
    }

    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        fun checksum(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
