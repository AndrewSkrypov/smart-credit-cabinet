@file:Suppress("ktlint:standard:no-wildcard-imports")

package ru.creditcabinet.servicing.infrastructure

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import ru.creditcabinet.agent.application.AgentApplicationService
import ru.creditcabinet.application.application.CreditCaller
import ru.creditcabinet.application.domain.ApplicationRepository
import ru.creditcabinet.application.infrastructure.creditPortFailure
import ru.creditcabinet.application.service.ApplicationService
import ru.creditcabinet.servicing.application.LoanServicingService
import ru.creditcabinet.servicing.contract.v1.ServicingOperations
import ru.creditcabinet.servicing.core.ServicingEngine
import ru.creditcabinet.servicing.domain.*
import ru.creditcabinet.servicing.grpc.ServicingGrpcClient
import ru.creditcabinet.servicing.grpc.servicingTlsChannel
import ru.creditcabinet.servicing.paymentreserve.application.PaymentReserveService
import ru.creditcabinet.servicing.paymentreserve.domain.PaymentReserveStore
import ru.creditcabinet.servicing.persistence.JdbcOpeningLedger
import ru.creditcabinet.servicing.persistence.JdbcServicingTransaction
import java.io.File
import java.time.Clock
import java.time.ZoneId

@Configuration
class ServicingConfiguration {
    @Bean("servicingOpeningDeliveryScheduler")
    fun servicingOpeningDeliveryScheduler(): org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler =
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler().apply {
            poolSize = 1
            setThreadNamePrefix("servicing-opening-delivery-")
            setWaitForTasksToCompleteOnShutdown(true)
            setAwaitTerminationSeconds(5)
        }

    @Bean fun servicingOperations(
        accounts: LoanAccountStore,
        plans: PaymentReserveStore,
        jdbc: JdbcTemplate,
        tx: TransactionTemplate,
        mapper: ObjectMapper,
        clock: Clock,
        @Value("\${cabinet.servicing.mode:local}") mode: String,
        @Value("\${cabinet.servicing.grpc.host:}") host: String,
        @Value("\${cabinet.servicing.grpc.port:9095}") port: Int,
        @Value("\${cabinet.servicing.grpc.certificate:}") certificate: String,
        @Value("\${cabinet.servicing.grpc.key:}") key: String,
        @Value("\${cabinet.servicing.grpc.server-ca:}") ca: String,
    ): ServicingOperations {
        require(mode in setOf("local", "remote")) { "Servicing mode must be local or remote" }
        if (mode == "local") return ServicingEngine(accounts, plans, JdbcOpeningLedger(jdbc, mapper), JdbcServicingTransaction(jdbc, tx, "LOCAL"), clock)
        require(host.isNotBlank() && port in 1..65535)
        require(listOf(certificate, key, ca).all { it.isNotBlank() && File(it).isFile }) { "Remote Servicing requires explicit mTLS credentials" }
        val accepted = jdbc.queryForObject(
            "select writer='REMOTE' and manifest_sha256 is not null from servicing_writer_fence where singleton=true",
            Boolean::class.java,
        )
        require(accepted == true) { "Remote Servicing requires accepted fenced migration" }
        return ServicingGrpcClient(servicingTlsChannel(host, port, File(certificate), File(key), File(ca)), mapper, {
            (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request?.getHeader("Authorization")
        }, authorizationFailure = ::creditPortFailure)
    }

    @Bean fun loanServicingService(
        operations: ServicingOperations,
        applications: ApplicationService,
        accounts: LoanAccountStore,
        jdbc: JdbcTemplate,
        tx: TransactionTemplate,
        callerContext: CreditCaller,
    ): LoanServicingService = LoanServicingService(operations, applications, callerContext) { application, loan ->
        require(operations is ServicingEngine) { "Legacy opening is disabled for the remote writer" }
        JdbcServicingTransaction(jdbc, tx, "LOCAL").execute {
            JdbcOpeningLedger(jdbc, com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()).lockApplication(application.id)
            accounts.findByApplicationId(application.id) ?: LoanAccount.open(
                application.id, application.company.id, loan.contractNumber,
                loan.amount, loan.termMonths, loan.rate, loan.revolving, loan.disbursedAt, loan.maturityDate,
                loan.disbursedAt.atZone(ZoneId.of("Europe/Moscow")).toLocalDate(),
            ).also { accounts.create(it) }
        }
    }

    @Bean fun paymentReserveService(
        operations: ServicingOperations,
        applications: ApplicationRepository,
        agentApplications: AgentApplicationService,
        clock: Clock,
        @Value("\${DEMO_ACCOUNTS:false}") demo: Boolean,
        callerContext: CreditCaller,
    ) =
        PaymentReserveService(operations, applications, agentApplications, clock, demo, callerContext)
}

@RestControllerAdvice
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
class CreditServicingFailureHttp {
    @ExceptionHandler(ServicingFailure::class)
    fun failure(e: ServicingFailure, request: jakarta.servlet.http.HttpServletRequest): ResponseEntity<ru.creditcabinet.common.dto.ErrorResponse> {
        val status = org.springframework.http.HttpStatus.valueOf(
            when (e.code) {
                "BAD_REQUEST" -> 400
                "NOT_FOUND" -> 404
                "CONFLICT" -> 409
                else -> 503
            },
        )
        return ResponseEntity.status(
            status,
        ).body(
            ru.creditcabinet.common.dto.ErrorResponse(
                status = status.value(),
                error = status.reasonPhrase,
                message = requireNotNull(e.message),
                path = request.requestURI,
            ),
        )
    }
}
