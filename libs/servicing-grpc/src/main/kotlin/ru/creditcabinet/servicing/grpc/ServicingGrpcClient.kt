@file:Suppress("ktlint:standard:no-wildcard-imports")

package ru.creditcabinet.servicing.grpc

import com.fasterxml.jackson.databind.ObjectMapper
import io.grpc.Channel
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.TlsChannelCredentials
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import io.grpc.stub.MetadataUtils
import ru.creditcabinet.common.exception.ForbiddenException
import ru.creditcabinet.common.exception.UnauthorizedException
import ru.creditcabinet.servicing.contract.v1.*
import ru.creditcabinet.servicing.domain.*
import ru.creditcabinet.servicing.paymentreserve.domain.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
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

val servicingAuthorization: Metadata.Key<String> = Metadata.Key.of("authorization",Metadata.ASCII_STRING_MARSHALLER)
fun servicingTlsChannel(host: String, port: Int, certificate: File, key: File, serverCa: File): ManagedChannel {
    require(host.isNotBlank() && port in 1..65535)
    val credentials = TlsChannelCredentials.newBuilder().keyManager(certificate,key).trustManager(serverCa).build()
    return NettyChannelBuilder.forAddress(host,port,credentials).disableRetry().build()
}

class ServicingGrpcClient(private val channel: Channel, mapper: ObjectMapper, private val authorization: () -> String?, private val deadlineMs: Long = 3000) : ServicingOperations, AutoCloseable {
    override fun close() { (channel as? ManagedChannel)?.shutdown() }
    private val client = ServicingServiceGrpc.newBlockingStub(channel)
    private val wire = ServicingWire(mapper)
    init { require(deadlineMs > 0) }
    private fun stub(): ServicingServiceGrpc.ServicingServiceBlockingStub {
        val headers = Metadata()
        authorization()?.let { headers.put(servicingAuthorization,it) }
        return client.withDeadlineAfter(deadlineMs,TimeUnit.MILLISECONDS).withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers))
    }
    override fun open(command: OpenLoanCommand): LoanOpenedReceipt = call {
        wire.decode(stub().openLoan(wire.encode(command,WireOpenLoanCommand.newBuilder())),LoanOpenedReceipt::class.java)
    }
    override fun byApplication(applicationId: UUID): LoanAccount? = call {
        val response = stub().byApplication(id(applicationId)); if(response.hasAccount()) wire.decode(response.account,LoanAccount::class.java) else null
    }
    override fun byId(loanId: UUID): LoanAccount? = call {
        val response = stub().byId(id(loanId)); if(response.hasAccount()) wire.decode(response.account,LoanAccount::class.java) else null
    }
    override fun draw(loanId: UUID, command: DrawCommand): LoanAccount = call {
        wire.decode(stub().draw(DrawRequest.newBuilder().setLoanId(loanId.toString()).setCommand(wire.encode<WireDrawCommand>(command,WireDrawCommand.newBuilder())).build()),LoanAccount::class.java)
    }
    override fun payNext(loanId: UUID, actor: ServicingActor): LoanAccount = call {
        wire.decode(stub().payNext(actor(loanId,actor)),LoanAccount::class.java)
    }
    override fun repayEarly(loanId: UUID, actor: ServicingActor): LoanAccount = call {
        wire.decode(stub().repayEarly(actor(loanId,actor)),LoanAccount::class.java)
    }
    override fun reserve(loanId: UUID): PaymentReserveQuote? = call {
        val response = stub().reserve(id(loanId)); if(response.hasQuote()) wire.decode(response.quote,PaymentReserveQuote::class.java) else null
    }
    override fun agentSummary(applicationId: UUID): AgentPaymentReserveSummary? = call {
        val response = stub().agentSummary(id(applicationId)); if(response.hasSummary()) wire.decode(response.summary,AgentPaymentReserveSummary::class.java) else null
    }
    override fun putReserve(loanId: UUID, command: ReserveCommand): PaymentReserveQuote = call {
        wire.decode(stub().putReserve(ReserveRequest.newBuilder().setLoanId(loanId.toString()).setCommand(wire.encode<WireReserveCommand>(command,WireReserveCommand.newBuilder())).build()),PaymentReserveQuote::class.java)
    }
    override fun receipt(loanId: UUID, command: ReceiptCommand): PaymentReserveQuote = call {
        wire.decode(stub().receipt(ReceiptRequest.newBuilder().setLoanId(loanId.toString()).setCommand(wire.encode<WireReceiptCommand>(command,WireReceiptCommand.newBuilder())).build()),PaymentReserveQuote::class.java)
    }
    override fun confirmReserve(loanId: UUID, command: ConfirmReserveCommand): PaymentReserveQuote = call {
        wire.decode(stub().confirmReserve(ConfirmRequest.newBuilder().setLoanId(loanId.toString()).setCommand(wire.encode<WireConfirmReserveCommand>(command,WireConfirmReserveCommand.newBuilder())).build()),PaymentReserveQuote::class.java)
    }
    private fun id(value: UUID) = IdRequest.newBuilder().setId(value.toString()).build()
    private fun actor(id: UUID, actor: ServicingActor) = ActorRequest.newBuilder().setLoanId(id.toString()).setActor(wire.encode<WireServicingActor>(actor,WireServicingActor.newBuilder())).build()
    private fun <T> call(action: () -> T): T = try { action() } catch(e: StatusRuntimeException) {
        throw when(e.status.code) {
            Status.Code.INVALID_ARGUMENT -> ServicingBadRequest("Некорректная операция обслуживания")
            Status.Code.NOT_FOUND -> ServicingNotFound("Кредитный счёт или резерв не найден")
            Status.Code.ABORTED,Status.Code.ALREADY_EXISTS -> ServicingConflict("Конфликт условий или состояния обслуживания")
            Status.Code.UNAUTHENTICATED -> UnauthorizedException()
            Status.Code.PERMISSION_DENIED -> ForbiddenException("Недостаточно прав для обслуживания")
            else -> ServicingUnavailable("Владелец текущего баланса недоступен")
        }
    } catch(e: IllegalArgumentException) { throw ServicingUnavailable("Владелец вернул несовместимый ответ") }
}
