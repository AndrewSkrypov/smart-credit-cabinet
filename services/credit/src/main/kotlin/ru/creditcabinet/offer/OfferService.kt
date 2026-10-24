package ru.creditcabinet.offer

import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.application.domain.ApplicationEvent
import ru.creditcabinet.application.domain.ApplicationStatus
import ru.creditcabinet.application.domain.LoanApplication
import ru.creditcabinet.application.domain.PipelineJobs
import ru.creditcabinet.application.domain.PipelineStep
import ru.creditcabinet.application.service.ApplicationService
import ru.creditcabinet.application.service.Transitions
import ru.creditcabinet.application.service.toActor
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.common.exception.ConflictException
import ru.creditcabinet.common.exception.ForbiddenException
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.integration.SigningGateway
import ru.creditcabinet.integration.SigningResult
import ru.creditcabinet.integration.SourceUnavailableException
import ru.creditcabinet.product.domain.ProductCatalog
import java.time.Clock
import java.util.UUID

/**
 * What the client does with an offer: accept or decline it, then sign the contract with a
 * one-time code. Repeating an action that already happened returns the current state, so a
 * double click or a retry never fails.
 */
@Service
class OfferService(
    private val applications: ApplicationService,
    private val transitions: Transitions,
    private val offers: OfferRepository,
    private val sessions: SigningSessions,
    private val gateway: SigningGateway,
    private val jobs: PipelineJobs,
    private val products: ProductCatalog,
    private val contracts: ContractTexts,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) {
    /** T16, then a signing code is issued right away (if the signing service is down, the client asks again). */
    fun accept(id: UUID): LoanApplication {
        val (claims, application) = applications.own(id)
        if (application.status == ApplicationStatus.SIGNING) return application
        val offer = issuedOffer(application)
        val now = clock.instant()
        if (offer.validUntil.isBefore(now)) throw ConflictException("Срок предложения истёк")
        val saved = tx.execute {
            contracts.freeze(application, offer, products.version(offer.productCode, offer.productVersion)!!)
            offers.updateStatus(offer.id, OfferStatus.ACCEPTED, now)
            transitions.commit(application.move(ApplicationEvent.ACCEPT_OFFER, claims.toActor()!!, now, payload = mapOf("offerId" to offer.id.toString())))
        }!!
        runCatching { startSigning(saved, offer, claims) }
        return saved
    }

    /** T17 by the client. */
    fun decline(id: UUID, comment: String?): LoanApplication {
        val (claims, application) = applications.own(id)
        if (application.status == ApplicationStatus.CLOSED) return application
        val offer = issuedOffer(application)
        val now = clock.instant()
        return tx.execute {
            offers.updateStatus(offer.id, OfferStatus.DECLINED, now)
            transitions.commit(application.move(ApplicationEvent.DECLINE_OFFER, claims.toActor()!!, now, comment = comment?.take(2000)))
        }!!
    }

    /** A new one-time code; the previous one stops working. */
    fun requestCode(id: UUID): SigningSessionRecord {
        val (claims, application) = applications.own(id)
        requireSigner(claims)
        if (application.status != ApplicationStatus.SIGNING) throw ConflictException("Подписывать сейчас нечего")
        val offer = offers.current(application.id)!!
        return try {
            startSigning(application, offer, claims)
        } catch (exception: SourceUnavailableException) {
            throw ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Сервис подписания недоступен — попробуйте через минуту")
        }
    }

    /** T18: the code is checked by the signing service; then the money is on its way. */
    fun sign(id: UUID, code: String): LoanApplication {
        val (claims, application) = applications.own(id)
        requireSigner(claims)
        if (application.status == ApplicationStatus.DISBURSING || application.status == ApplicationStatus.DISBURSED) return application
        if (application.status != ApplicationStatus.SIGNING) throw ConflictException("Подписывать сейчас нечего")
        val session = sessions.latest(application.id)?.takeIf { it.signatureId == null } ?: throw ConflictException("Сначала получите код подписи")
        val currentOffer = offers.current(id) ?: throw ConflictException("Предложение не найдено")
        if (currentOffer.id != session.offerId || currentOffer.status != OfferStatus.ACCEPTED) throw ConflictException("Код относится к другому предложению")
        val snapshot = contracts.find(session.offerId)
        if (snapshot == null ||
            session.contractSnapshotId != snapshot.id
        ) {
            throw ConflictException("Текст для прежнего кода не сохранён — получите новый код подписи")
        }
        if (session.expiresAt.isBefore(clock.instant())) throw ConflictException("Срок действия кода истёк — запросите новый")
        val result = try {
            gateway.confirm(session.externalSessionId, code.trim())
        } catch (exception: SourceUnavailableException) {
            throw ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Сервис подписания недоступен — попробуйте через минуту")
        }
        return when (result) {
            is SigningResult.Refused -> throw BadRequestException(result.message)
            is SigningResult.Signed -> tx.execute {
                val now = clock.instant()
                sessions.markSigned(session.id, result.signatureId, result.signedAt, claims.userId)
                val saved = transitions.commit(
                    application.move(ApplicationEvent.SIGN, claims.toActor()!!, now, payload = mapOf("signatureId" to result.signatureId.toString())),
                )
                jobs.enqueue(saved.id, PipelineStep.DISBURSEMENT, now)
                saved
            }!!
        }
    }

    fun current(applicationId: UUID): Offer? = offers.current(applicationId)

    fun contract(id: UUID): ContractTextView {
        val application = applications.get(id)
        val offer = offers.current(id) ?: throw ru.creditcabinet.common.exception.NotFoundException("Текста пока нет: банк ещё не сделал предложение")
        val snapshot = contracts.find(offer.id)
        val session = sessions.latest(id)?.takeIf { it.offerId == offer.id }
        val legacy = snapshot == null && application.status != ApplicationStatus.OFFER_ISSUED
        val text = snapshot?.text ?: contracts.render(application, offer, products.version(offer.productCode, offer.productVersion)!!)
        return ContractTextView(
            snapshot,
            if (legacy) "Сводка сохранённых условий. Исходный подписываемый текст не сохранён.\n" + text.substringAfter('\n') else text,
            legacy,
            snapshot != null && session?.contractSnapshotId == snapshot.id && session?.signatureId == null && application.status == ApplicationStatus.SIGNING,
            session?.signedAt,
        )
    }

    private fun issuedOffer(application: LoanApplication): Offer {
        if (application.status != ApplicationStatus.OFFER_ISSUED) throw ConflictException("Предложения банка сейчас нет")
        return offers.current(application.id)?.takeIf { it.status == OfferStatus.ISSUED } ?: throw ConflictException("Предложения банка сейчас нет")
    }

    private fun requireSigner(claims: TokenClaims) {
        if (!claims.canSign) throw ForbiddenException("Подписать договор может только представитель компании с правом подписи")
    }

    private fun startSigning(application: LoanApplication, offer: Offer, claims: TokenClaims): SigningSessionRecord {
        val product = products.version(offer.productCode, offer.productVersion)!!
        val snapshot = contracts.freeze(application, offer, product)
        val started = gateway.start(
            "contract-${application.id}",
            "Кредитный договор по заявке № ${application.number}",
            snapshot.text,
            claims.fullName ?: "Представитель компании",
        )
        val record = SigningSessionRecord(
            applicationId = application.id,
            offerId = offer.id,
            externalSessionId = started.sessionId,
            code = started.code,
            expiresAt = started.expiresAt,
            createdAt = clock.instant(),
            contractSnapshotId = snapshot.id,
        )
        sessions.save(record)
        return record
    }
}
