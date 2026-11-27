package ru.creditcabinet.document.generated.infrastructure

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import ru.creditcabinet.document.generated.application.GeneratedArtifactFailures
import java.util.UUID

@Component
class LoggedGeneratedArtifactFailures : GeneratedArtifactFailures {
    private val log = LoggerFactory.getLogger("ru.creditcabinet.document.generated.GeneratedArtifacts")

    override fun preparation(applicationId: UUID, failure: Throwable) {
        log.warn("Artifact preparation failed for {}: {}", applicationId, failure.javaClass.simpleName)
    }

    override fun generation(artifactId: UUID, failure: Throwable) {
        log.warn("Artifact generation failed for {}: {}", artifactId, failure.javaClass.simpleName)
    }
}
