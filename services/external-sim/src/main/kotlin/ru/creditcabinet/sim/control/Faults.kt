package ru.creditcabinet.sim.control

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.sim.dataset.FaultMode
import ru.creditcabinet.sim.dataset.Source
import ru.creditcabinet.sim.dataset.SyntheticCompany
import java.util.concurrent.ConcurrentHashMap

/**
 * How each simulated system behaves right now. The control panel switches a source to SLOW
 * (answers later than any client waits) or DOWN (503), to show on a live demo that the pipeline
 * retries and the application waits instead of failing. A company can also carry a permanent
 * fault in the dataset. Every answer also takes a realistic [latencyMs].
 */
@Component
class Faults(
    @Value("\${cabinet.sim.latency-ms:200}") private val latencyMs: Long,
    @Value("\${cabinet.sim.slow-delay-ms:8000}") val slowDelayMs: Long,
) {
    private val modes = ConcurrentHashMap<Source, FaultMode>()

    fun mode(source: Source): FaultMode = modes[source] ?: FaultMode.OK

    fun set(source: Source, mode: FaultMode) {
        modes[source] = mode
    }

    fun reset() = modes.clear()

    /** Called first thing by every simulated endpoint: delays, or fails the way the real system would. */
    fun enter(source: Source, company: SyntheticCompany? = null) {
        val mode = company?.faults?.get(source)?.takeIf { it != FaultMode.OK } ?: mode(source)
        when (mode) {
            FaultMode.DOWN -> throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "${source.title}: сервис недоступен (симуляция сбоя)")
            FaultMode.SLOW -> Thread.sleep(slowDelayMs)
            FaultMode.OK -> if (latencyMs > 0) Thread.sleep(latencyMs)
        }
    }
}
