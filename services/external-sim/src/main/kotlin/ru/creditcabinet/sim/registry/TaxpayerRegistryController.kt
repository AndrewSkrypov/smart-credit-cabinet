package ru.creditcabinet.sim.registry

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.common.dto.ErrorResponse
import ru.creditcabinet.common.model.Inn
import ru.creditcabinet.sim.control.Faults
import ru.creditcabinet.sim.dataset.Dataset
import ru.creditcabinet.sim.dataset.Source

data class TaxpayerRegistration(val registered: Boolean)

@Tag(name = "ЕГРН ФНС (симулятор)")
@RestController
@RequestMapping("/ext/registry/v1")
class TaxpayerRegistryController(private val dataset: Dataset, private val faults: Faults) {
    @Operation(
        summary = "Проверка постановки на налоговый учёт в ЕГРН ФНС по ИНН",
        responses = [
            ApiResponse(responseCode = "200", description = "Результат проверки записи",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = TaxpayerRegistration::class))]),
            ApiResponse(responseCode = "400", description = "Некорректный ИНН",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))]),
            ApiResponse(responseCode = "503", description = "ЕГРН ФНС временно недоступен",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = ErrorResponse::class))]),
        ],
    )
    @GetMapping("/taxpayer-registration")
    fun taxpayerRegistration(@RequestParam inn: String): TaxpayerRegistration {
        val number = Inn.parse(inn).value
        val company = dataset.find(number)
        faults.enter(Source.TAX_REGISTRY, company)
        return TaxpayerRegistration(registered = company != null && number != NOT_REGISTERED_IN_EGRN)
    }

    private companion object {
        // The synthetic business exists in ЕГРЮЛ but has no ЕГРН record in this scenario.
        const val NOT_REGISTERED_IN_EGRN = "0000000018"
    }
}
