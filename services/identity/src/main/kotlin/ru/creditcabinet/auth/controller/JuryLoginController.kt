package ru.creditcabinet.auth.controller

import io.swagger.v3.oas.annotations.Operation
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.auth.application.JuryLoginService
import ru.creditcabinet.auth.domain.JuryLoginAccount
import ru.creditcabinet.auth.dto.JuryLinkRequest
import ru.creditcabinet.auth.dto.UserSessionResponse
import ru.creditcabinet.identity.domain.IdentityFailure.NotFound

@RestController
@RequestMapping("/api/v1/auth/jury-link")
class JuryLoginController(
    private val juryLogin: JuryLoginService,
    @Value("\${cabinet.auth.jury-login-enabled:false}") private val juryLoginEnabled: Boolean,
    @Value("\${cabinet.auth.shared-demo-accounts:true}") private val sharedDemoAccounts: Boolean,
) {
    @Operation(hidden = true)
    @PostMapping("/prepare")
    fun prepare(@RequestBody request: JuryLinkRequest, response: HttpServletResponse): JuryLoginAccount {
        requireJuryLogin(response)
        return juryLogin.inspect(request.token)
    }

    @Operation(hidden = true)
    @PostMapping("/redeem")
    fun redeem(@RequestBody request: JuryLinkRequest, response: HttpServletResponse): UserSessionResponse {
        requireJuryLogin(response)
        return juryLogin.redeem(request.token).toResponse()
    }

    private fun requireJuryLogin(response: HttpServletResponse) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store")
        if (!juryLoginEnabled || sharedDemoAccounts) throw NotFound("Страница не найдена")
    }
}
