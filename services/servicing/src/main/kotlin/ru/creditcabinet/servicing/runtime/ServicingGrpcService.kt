@file:Suppress("ktlint:standard:no-wildcard-imports")

package ru.creditcabinet.servicing.runtime

import com.fasterxml.jackson.databind.ObjectMapper
import io.grpc.Context
import io.grpc.Status
import io.grpc.stub.StreamObserver
import org.springframework.dao.DataAccessException
import ru.creditcabinet.common.exception.ForbiddenException
import ru.creditcabinet.common.exception.UnauthorizedException
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.common.security.TokenClaims
import ru.creditcabinet.servicing.contract.v1.*
import ru.creditcabinet.servicing.core.ServicingEngine
import ru.creditcabinet.servicing.domain.*
import ru.creditcabinet.servicing.grpc.ServicingWire
import java.util.UUID
import ru.creditcabinet.contracts.servicing.v1.ServicingServiceGrpc
import ru.creditcabinet.contracts.servicing.v1.IdRequest
import ru.creditcabinet.contracts.servicing.v1.AccountResponse
import ru.creditcabinet.contracts.servicing.v1.ActorRequest
import ru.creditcabinet.contracts.servicing.v1.DrawRequest
import ru.creditcabinet.contracts.servicing.v1.ReserveRequest
import ru.creditcabinet.contracts.servicing.v1.ReceiptRequest
import ru.creditcabinet.contracts.servicing.v1.ConfirmRequest
import ru.creditcabinet.contracts.servicing.v1.ReserveResponse
import ru.creditcabinet.contracts.servicing.v1.AgentSummaryResponse
import ru.creditcabinet.contracts.servicing.v1.OpenLoanCommand as WireOpenLoanCommand
import ru.creditcabinet.contracts.servicing.v1.LoanOpenedReceipt as WireLoanOpenedReceipt
import ru.creditcabinet.contracts.servicing.v1.LoanAccount as WireLoanAccount
import ru.creditcabinet.contracts.servicing.v1.DrawCommand as WireDrawCommand
import ru.creditcabinet.contracts.servicing.v1.ServicingActor as WireServicingActor
import ru.creditcabinet.contracts.servicing.v1.ReserveCommand as WireReserveCommand
import ru.creditcabinet.contracts.servicing.v1.ReceiptCommand as WireReceiptCommand
import ru.creditcabinet.contracts.servicing.v1.ConfirmReserveCommand as WireConfirmReserveCommand
import ru.creditcabinet.contracts.servicing.v1.PaymentReserveQuote as WirePaymentReserveQuote
import ru.creditcabinet.contracts.servicing.v1.AgentPaymentReserveSummary as WireAgentPaymentReserveSummary

internal val servicingBearer: Context.Key<String> = Context.key("servicing-bearer")

class ServicingGrpcService(private val engine: ServicingEngine, private val verifier: JwtVerifier, mapper: ObjectMapper) : ServicingServiceGrpc.ServicingServiceImplBase() {
    private val wire = ServicingWire(mapper)
    // No user token on producer RPC: mTLS Credit service identity is mandatory on every call.
    override fun openLoan(request: WireOpenLoanCommand, observer: StreamObserver<WireLoanOpenedReceipt>) = respond(observer) {
        wire.encode(engine.open(wire.decode(request,OpenLoanCommand::class.java)),WireLoanOpenedReceipt.newBuilder())
    }
    override fun byApplication(request: IdRequest, observer: StreamObserver<AccountResponse>) = respond(observer) {
        val caller = verifier.requireRole(servicingBearer.get(),Roles.CLIENT,Roles.AGENT,*Roles.BANK_STAFF.toTypedArray())
        val account = engine.byApplication(UUID.fromString(request.id))
        if(account != null && caller.isClient && caller.companyId != account.companyId) throw ServicingNotFound("Счёт не найден")
        AccountResponse.newBuilder().apply { account?.let { setAccount(wire.encode<WireLoanAccount>(it,WireLoanAccount.newBuilder())) } }.build()
    }
    override fun byId(request: IdRequest, observer: StreamObserver<AccountResponse>) = respond(observer) {
        val caller = verifier.requireRole(servicingBearer.get(),Roles.CLIENT)
        val account = engine.byId(UUID.fromString(request.id))
        if(account != null && account.companyId != caller.companyId) throw ServicingNotFound("Счёт не найден")
        AccountResponse.newBuilder().apply { account?.let { setAccount(wire.encode<WireLoanAccount>(it,WireLoanAccount.newBuilder())) } }.build()
    }
    override fun draw(request: DrawRequest, observer: StreamObserver<WireLoanAccount>) = respond(observer) {
        require(request.hasCommand() && request.command.hasActor())
        val command = wire.decode(request.command,DrawCommand::class.java); actor(command.actor)
        wire.encode(engine.draw(UUID.fromString(request.loanId),command),WireLoanAccount.newBuilder())
    }
    override fun payNext(request: ActorRequest, observer: StreamObserver<WireLoanAccount>) = respond(observer) {
        require(request.hasActor()); val who = wire.decode(request.actor,ServicingActor::class.java); actor(who)
        wire.encode(engine.payNext(UUID.fromString(request.loanId),who),WireLoanAccount.newBuilder())
    }
    override fun repayEarly(request: ActorRequest, observer: StreamObserver<WireLoanAccount>) = respond(observer) {
        require(request.hasActor()); val who = wire.decode(request.actor,ServicingActor::class.java); actor(who)
        wire.encode(engine.repayEarly(UUID.fromString(request.loanId),who),WireLoanAccount.newBuilder())
    }
    override fun reserve(request: IdRequest, observer: StreamObserver<ReserveResponse>) = respond(observer) {
        val caller = verifier.requireRole(servicingBearer.get(),Roles.CLIENT)
        val id = UUID.fromString(request.id)
        if(engine.byId(id)?.companyId != caller.companyId) throw ServicingNotFound("Счёт не найден")
        ReserveResponse.newBuilder().apply { engine.reserve(id)?.let { setQuote(wire.encode<WirePaymentReserveQuote>(it,WirePaymentReserveQuote.newBuilder())) } }.build()
    }
    override fun agentSummary(request: IdRequest, observer: StreamObserver<AgentSummaryResponse>) = respond(observer) {
        verifier.requireRole(servicingBearer.get(),Roles.AGENT)
        // Trusted Credit has just checked the current application mandate; the response has no money fields.
        AgentSummaryResponse.newBuilder().apply { engine.agentSummary(UUID.fromString(request.id))?.let { setSummary(wire.encode<WireAgentPaymentReserveSummary>(it,WireAgentPaymentReserveSummary.newBuilder())) } }.build()
    }
    override fun putReserve(request: ReserveRequest, observer: StreamObserver<WirePaymentReserveQuote>) = respond(observer) {
        require(request.hasCommand() && request.command.hasActor()); val command = wire.decode(request.command,ReserveCommand::class.java); actor(command.actor)
        wire.encode(engine.putReserve(UUID.fromString(request.loanId),command),WirePaymentReserveQuote.newBuilder())
    }
    override fun receipt(request: ReceiptRequest, observer: StreamObserver<WirePaymentReserveQuote>) = respond(observer) {
        require(request.hasCommand() && request.command.hasActor()); val command = wire.decode(request.command,ReceiptCommand::class.java); actor(command.actor)
        wire.encode(engine.receipt(UUID.fromString(request.loanId),command),WirePaymentReserveQuote.newBuilder())
    }
    override fun confirmReserve(request: ConfirmRequest, observer: StreamObserver<WirePaymentReserveQuote>) = respond(observer) {
        require(request.hasCommand() && request.command.hasActor()); val command = wire.decode(request.command,ConfirmReserveCommand::class.java); actor(command.actor)
        wire.encode(engine.confirmReserve(UUID.fromString(request.loanId),command),WirePaymentReserveQuote.newBuilder())
    }
    private fun actor(actor: ServicingActor) {
        val caller = verifier.requireRole(servicingBearer.get(),Roles.CLIENT)
        if(caller.userId != actor.userId || caller.companyId != actor.companyId) throw ForbiddenException("Делегированный клиент не совпадает с владельцем операции")
    }
    private fun <T> respond(observer: StreamObserver<T>, action: () -> T) {
        val value = try { action() } catch(e: Exception) {
            val status = when(e) {
                is UnauthorizedException -> Status.UNAUTHENTICATED
                is ForbiddenException -> Status.PERMISSION_DENIED
                is ServicingFailure -> when(e.code) { "CONFLICT" -> Status.ABORTED; "BAD_REQUEST" -> Status.INVALID_ARGUMENT; "NOT_FOUND" -> Status.NOT_FOUND; else -> Status.UNAVAILABLE }
                is IllegalArgumentException, is com.fasterxml.jackson.core.JsonProcessingException, is com.google.protobuf.InvalidProtocolBufferException -> Status.INVALID_ARGUMENT
                is DataAccessException -> Status.UNAVAILABLE
                else -> Status.INTERNAL
            }
            observer.onError(status.asRuntimeException()); return
        }
        observer.onNext(value); observer.onCompleted()
    }
}
