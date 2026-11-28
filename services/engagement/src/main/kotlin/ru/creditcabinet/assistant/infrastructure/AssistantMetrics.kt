package ru.creditcabinet.assistant.infrastructure

import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Timer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.creditcabinet.assistant.application.AnswerMetrics
import ru.creditcabinet.assistant.application.AnswerOperation
import ru.creditcabinet.assistant.domain.Answer

@Component
class AssistantMetrics(private val registry: MeterRegistry) : AnswerMetrics {
    private val log = LoggerFactory.getLogger("ru.creditcabinet.assistant.AssistantService")

    override fun measure(operation: AnswerOperation, answer: () -> Answer): Answer {
        val sample = Timer.start(registry)
        var outcome = "ERROR"
        try {
            return answer().also { outcome = it.mode.name }
        } finally {
            sample.stop(
                Timer.builder("cabinet.assistant.answer")
                    .tag("operation", operation.tag)
                    .tag("outcome", outcome)
                    .publishPercentileHistogram()
                    .register(registry),
            )
        }
    }

    override fun modelEvent(outcome: String) = registry.counter("cabinet.assistant.model", "outcome", outcome).increment()

    override fun rejected(operation: AnswerOperation) {
        val subject = when (operation) {
            AnswerOperation.MESSAGE -> "Assistant answer"
            AnswerOperation.UNDERWRITING_SUMMARY -> "Underwriting summary"
        }
        log.warn("$subject dropped: reference validation failed")
    }
}
