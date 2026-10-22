package ru.creditcabinet.sim.control

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Configuration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer
import ru.creditcabinet.common.exception.ForbiddenException
import ru.creditcabinet.sim.dataset.Dataset
import ru.creditcabinet.sim.dataset.FaultMode
import ru.creditcabinet.sim.dataset.Scenario
import ru.creditcabinet.sim.dataset.Source

data class SourceState(val source: Source, val title: String, val mode: FaultMode)

data class ScenarioCompany(val inn: String, val name: String, val scenario: Scenario?)

data class ControlState(val sources: List<SourceState>, val slowDelayMs: Long, val companies: List<ScenarioCompany>)

data class ModeRequest(val mode: FaultMode)

/**
 * The demo control panel: the test companies with what each demonstrates, and switches that
 * make a source slow or unavailable. Published by the gateway at /sim/ for the demo; set
 * SIM_CONTROL_ENABLED=false to keep the switches closed elsewhere.
 */
@Tag(name = "Пульт симуляторов")
@RestController
@RequestMapping("/sim/control")
class ControlController(
    private val faults: Faults,
    private val dataset: Dataset,
    @Value("\${cabinet.sim.control-enabled:true}") private val enabled: Boolean,
) {
    @Operation(summary = "Состояние синтетических источников и десять демонстрационных компаний")
    @GetMapping
    fun state(): ControlState =
        ControlState(
            sources = Source.entries.map { SourceState(it, it.title, faults.mode(it)) },
            slowDelayMs = faults.slowDelayMs,
            companies = dataset.scenarios.map { ScenarioCompany(it.inn, it.shortName, it.scenario) },
        )

    @Operation(summary = "Переключить источник: OK, SLOW (отвечает дольше таймаута клиента) или DOWN (503)")
    @PutMapping("/sources/{source}")
    fun set(@PathVariable source: Source, @RequestBody request: ModeRequest): ControlState {
        requireEnabled()
        faults.set(source, request.mode)
        return state()
    }

    @Operation(summary = "Вернуть все синтетические источники в нормальный режим")
    @PostMapping("/reset")
    fun reset(): ControlState {
        requireEnabled()
        faults.reset()
        return state()
    }

    private fun requireEnabled() {
        if (!enabled) throw ForbiddenException("Пульт симуляторов выключен в этом окружении")
    }
}

@Configuration
class PanelPage : WebMvcConfigurer {
    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addViewController("/sim/").setViewName("forward:/sim/index.html")
        registry.addRedirectViewController("/sim", "/sim/")
    }
}
