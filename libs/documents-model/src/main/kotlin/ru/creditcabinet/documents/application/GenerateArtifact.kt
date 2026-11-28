package ru.creditcabinet.documents.application

import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.generated.ArtifactKind
import ru.creditcabinet.documents.domain.ArtifactLifecycle
import java.time.Clock

/** Frozen-input generation never reloads current credit terms or borrower details. */
class GenerateArtifact(
    private val jobs: ArtifactJobs,
    private val input: FrozenArtifactInput,
    private val renderer: RenderArtifact,
    private val files: FileStore,
    private val lifecycle: ArtifactLifecycle,
    private val clock: Clock,
) {
    fun execute() {
        val claim = jobs.claim(lifecycle) ?: return
        val record = claim.record
        try {
            val frozen = input.decode(record.inputJson)
            lifecycle.verifyFrozenInput(record)
            val rendered = renderer.render(ArtifactKind.valueOf(record.kind), frozen)
            lifecycle.validateRendered(rendered)
            jobs.publish(claim) {
                files.put(record.storedName, rendered.inputStream(), rendered.size.toLong(), StorageBackend.valueOf(record.backend))
                lifecycle.ready(record, rendered, clock.instant())
            }
        } catch (_: Exception) {
            // Losing ownership or the claim must never publish ERROR for a later worker.
            runCatching { jobs.fail(claim, lifecycle.failureRetrySeconds) }
        }
    }
}
