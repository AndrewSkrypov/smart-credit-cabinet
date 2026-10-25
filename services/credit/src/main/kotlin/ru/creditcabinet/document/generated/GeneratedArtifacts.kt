package ru.creditcabinet.document.generated

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.service.ApplicationService
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.NotFoundException
import ru.creditcabinet.common.notifications.NotificationEmail
import ru.creditcabinet.common.notifications.NotificationEmailKind
import ru.creditcabinet.common.notifications.PlatformNotification
import ru.creditcabinet.common.notifications.PlatformNotifications
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.offer.ContractTexts
import ru.creditcabinet.offer.Loans
import ru.creditcabinet.offer.OfferRepository
import ru.creditcabinet.offer.SigningSessions
import ru.creditcabinet.product.domain.LoanMath
import ru.creditcabinet.product.domain.ProductCatalog
import java.security.MessageDigest
import java.sql.ResultSet
import java.sql.Timestamp
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
    private val jdbc: JdbcTemplate,
    private val mapper: ObjectMapper,
    private val applications: ApplicationService,
    private val repository: ApplicationRepository,
    private val offers: OfferRepository,
    private val loans: Loans,
    private val products: ProductCatalog,
    private val contracts: ContractTexts,
    private val signing: SigningSessions,
    private val files: FileStore,
    private val renderer: ArtifactRenderer,
    private val tx: TransactionTemplate,
    private val clock: Clock,
    private val notifications: PlatformNotifications,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    fun list(applicationId: UUID): List<ArtifactView> = prepare(applications.get(applicationId))

    private fun prepare(application: LoanApplication): List<ArtifactView> {
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
        val inputJson = mapper.writeValueAsString(input)
        val hash = checksum(inputJson.toByteArray(Charsets.UTF_8))
        val now = Timestamp.from(clock.instant())
        val kinds = ArtifactKind.entries.filter { loan != null || it != ArtifactKind.CONTRACT_PDF }
        for (kind in kinds) {
            val id = UUID.randomUUID()
            jdbc.update(
                """
                insert into generated_artifacts(id,application_id,offer_id,kind,phase,template_version,input_hash,input_json,title,file_name,content_type,stored_name,storage_backend,next_attempt_at,created_at,updated_at)
                values(?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?) on conflict(application_id,offer_id,kind,phase,template_version,input_hash) do nothing
                """.trimIndent(),
                id, application.id, offer.id, kind.name, phase.name, ArtifactRenderer.TEMPLATE_VERSION, hash, inputJson,
                renderer.title(kind, input), "application-${application.number}-${kind.name.lowercase()}.${kind.extension}", kind.contentType,
                "generated-$id.${kind.extension}", files.writeBackend.name, now, now, now,
            )
        }
        return jdbc.query(
            "select * from generated_artifacts where application_id=? and offer_id=? and phase=? and input_hash=? and template_version=? order by kind",
            { rs, _ -> view(rs) },
            application.id,
            offer.id,
            phase.name,
            hash,
            ArtifactRenderer.TEMPLATE_VERSION,
        )
    }

    /** Independent from issuing money: an unavailable renderer/store cannot roll back disbursement. */
    fun prepareFinalLoans() {
        val ids = jdbc.query(
            """
            select l.application_id from loans l where
            (select count(*) from generated_artifacts a where a.application_id=l.application_id and a.phase='FINAL' and a.template_version=?) < 3
            order by l.disbursed_at limit 5
            """.trimIndent(),
            { rs, _ -> rs.getObject(1, UUID::class.java) },
            ArtifactRenderer.TEMPLATE_VERSION,
        )
        ids.forEach { id ->
            runCatching { tx.executeWithoutResult { prepare(repository.find(id) ?: throw NotFoundException("Заявка не найдена")) } }
                .onFailure { log.warn("Artifact preparation failed for {}: {}", id, it.javaClass.simpleName) }
        }
    }

    /** All files must be committed READY; claim and outbox write are one transaction. */
    fun notifyReadyGroups(): Int {
        val groups = jdbc.queryForList(
            """
            select a.application_id,a.offer_id,a.template_version,a.input_hash
            from generated_artifacts a
            where a.phase='FINAL' and a.status='READY' and a.template_version=?
              and not exists(select 1 from generated_artifact_notices n where n.application_id=a.application_id
                and n.offer_id=a.offer_id and n.template_version=a.template_version and n.input_hash=a.input_hash)
            group by a.application_id,a.offer_id,a.template_version,a.input_hash having count(*)=3
            order by a.application_id limit 10
            """.trimIndent(),
            ArtifactRenderer.TEMPLATE_VERSION,
        )
        return groups.count { group ->
            tx.execute {
                val id = group["application_id"] as UUID
                val offerId = group["offer_id"] as UUID
                val hash = group["input_hash"].toString()
                val statuses = jdbc.queryForList(
                    "select status from generated_artifacts where application_id=? and offer_id=? and phase='FINAL' and template_version=? and input_hash=? order by kind for update",
                    String::class.java,
                    id,
                    offerId,
                    ArtifactRenderer.TEMPLATE_VERSION,
                    hash,
                )
                if (statuses.size != 3 || statuses.any { it != "READY" }) return@execute false
                val claimed = jdbc.update(
                    "insert into generated_artifact_notices(application_id,offer_id,template_version,input_hash,queued_at) values(?,?,?,?,?) on conflict do nothing",
                    id,
                    offerId,
                    ArtifactRenderer.TEMPLATE_VERSION,
                    hash,
                    Timestamp.from(clock.instant()),
                )
                if (claimed == 0) return@execute false
                val application = repository.find(id) ?: throw NotFoundException("Заявка не найдена")
                notifications.send(
                    listOf(
                        PlatformNotification(
                            recipientCompanyId = application.company.id,
                            type = PlatformNotification.APPLICATION_STATUS,
                            title = "Документы по кредиту готовы",
                            body = "В кабинете доступны PDF сохранённых условий и график платежей в PDF и XLSX.",
                            link = "/client/applications/$id",
                            dedupKey = "generated-files:$id:$offerId:${ArtifactRenderer.TEMPLATE_VERSION}:$hash",
                            email = NotificationEmail(NotificationEmailKind.FILES_READY),
                        ),
                    ),
                )
                true
            }!!
        }
    }

    /** Row lock spans the bounded write; competing workers skip it, retries keep the same object name. */
    fun generateOne(): Boolean = tx.execute {
        val row = jdbc.queryForList(
            "select * from generated_artifacts where status <> 'READY' and attempts < 5 and next_attempt_at <= ? order by next_attempt_at limit 1 for update skip locked",
            Timestamp.from(clock.instant()),
        ).firstOrNull() ?: return@execute false
        val id = row["id"] as UUID
        val now = clock.instant()
        try {
            val input = mapper.readValue<ArtifactInput>(row["input_json"].toString())
            require(checksum(mapper.writeValueAsBytes(input)) == row["input_hash"].toString()) { "Generated input checksum mismatch" }
            val bytes = renderer.render(ArtifactKind.valueOf(row["kind"].toString()), input)
            require(bytes.isNotEmpty() && bytes.size <= MAX_BYTES) { "Generated file size is invalid" }
            files.put(row["stored_name"].toString(), bytes.inputStream(), bytes.size.toLong(), StorageBackend.valueOf(row["storage_backend"].toString()))
            jdbc.update(
                "update generated_artifacts set status='READY',size_bytes=?,checksum=?,attempts=attempts+1,error_code=null,updated_at=?,ready_at=? where id=?",
                bytes.size.toLong(),
                checksum(bytes),
                Timestamp.from(now),
                Timestamp.from(now),
                id,
            )
        } catch (failure: Exception) {
            jdbc.update(
                "update generated_artifacts set status='ERROR',attempts=attempts+1,error_code='GENERATION_FAILED',next_attempt_at=?,updated_at=? where id=?",
                Timestamp.from(now.plusSeconds(15)),
                Timestamp.from(now),
                id,
            )
            log.warn("Artifact generation failed for {}: {}", id, failure.javaClass.simpleName)
        }
        true
    }!!

    @Transactional
    fun retry(applicationId: UUID, artifactId: UUID): ArtifactView {
        applications.get(applicationId)
        val record =
            jdbc.query("select * from generated_artifacts where application_id=? and id=? for update", { rs, _ ->
                view(rs)
            }, applicationId, artifactId).firstOrNull()
                ?: throw NotFoundException("Файл не найден")
        if (record.status == ArtifactStatus.READY) return record
        jdbc.update(
            "update generated_artifacts set status='PENDING',attempts=0,error_code=null,next_attempt_at=?,updated_at=? where id=?",
            Timestamp.from(clock.instant()),
            Timestamp.from(clock.instant()),
            artifactId,
        )
        return record.copy(status = ArtifactStatus.PENDING, errorMessage = null, downloadUrl = null)
    }

    fun download(applicationId: UUID, artifactId: UUID): ArtifactDownload {
        applications.get(applicationId)
        val metadata =
            jdbc.query("select * from generated_artifacts where application_id=? and id=?", { rs, _ -> view(rs) }, applicationId, artifactId).firstOrNull()
                ?: throw NotFoundException("Файл не найден")
        if (metadata.status != ArtifactStatus.READY) throw ConflictException("Файл ещё не готов")
        val storage = jdbc.queryForMap("select stored_name,storage_backend from generated_artifacts where id=?", artifactId)
        try {
            val file = files.get(storage["stored_name"].toString(), StorageBackend.valueOf(storage["storage_backend"].toString()))
                ?: throw java.io.IOException("Generated file missing")
            val bytes = file.content.use { it.readNBytes(MAX_BYTES + 1) }
            require(bytes.size.toLong() == metadata.sizeBytes && bytes.size.toLong() == file.sizeBytes && checksum(bytes) == metadata.checksum) {
                "Generated file checksum mismatch"
            }
            return ArtifactDownload(metadata, bytes)
        } catch (failure: Exception) {
            jdbc.update(
                "update generated_artifacts set status='ERROR',error_code='STORAGE_UNAVAILABLE',attempts=0,next_attempt_at=?,updated_at=? where id=?",
                Timestamp.from(clock.instant().plusSeconds(15)),
                Timestamp.from(clock.instant()),
                artifactId,
            )
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Файл временно недоступен. Обновите список и повторите создание файла.")
        }
    }

    private fun view(rs: ResultSet): ArtifactView {
        val status = ArtifactStatus.valueOf(rs.getString("status"))
        val id = rs.getObject("id", UUID::class.java)
        val application = rs.getObject("application_id", UUID::class.java)
        return ArtifactView(
            id,
            ArtifactKind.valueOf(
                rs.getString("kind"),
            ),
            ArtifactPhase.valueOf(rs.getString("phase")), rs.getString("title"), rs.getString("file_name"), rs.getString("content_type"),
            status,
            rs.getObject(
                "size_bytes",
                java.lang.Long::class.java,
            )?.toLong(),
            rs.getString("checksum"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("ready_at")?.toInstant(),
            if (status == ArtifactStatus.ERROR) "Не удалось подготовить файл. Можно повторить создание." else null,
            if (status == ArtifactStatus.READY) "/api/v1/applications/$application/artifacts/$id/download" else null,
        )
    }

    companion object {
        const val MAX_BYTES = 20 * 1024 * 1024
        fun checksum(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
