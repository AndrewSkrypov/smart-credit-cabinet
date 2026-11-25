package ru.creditcabinet.assistant

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.springframework.stereotype.Component

@Component
class AssistantMetrics(private val registry: MeterRegistry) {
    fun measure(operation: String, answer: () -> Answer): Answer {
        val sample = Timer.start(registry)
        var outcome = "ERROR"
        try {
            return answer().also { outcome = it.mode.name }
        } finally {
            sample.stop(
                Timer.builder("cabinet.assistant.answer")
                    .tag("operation", operation)
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(registry),
            )
        }
    }

    fun modelEvent(outcome: String) = registry.counter("cabinet.assistant.model", "outcome", outcome).increment()
}
