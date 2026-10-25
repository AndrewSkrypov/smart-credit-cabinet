package ru.creditcabinet.document.generated

import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import ru.creditcabinet.pipeline.PipelineSettings
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Owned worker keeps rendering/storage latency out of the credit pipeline's polling thread. */
@Component
class ArtifactWorker(private val artifacts: GeneratedArtifacts, private val settings: PipelineSettings) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, "generated-files").apply { isDaemon = true } }

    @Scheduled(fixedDelayString = "\${cabinet.artifacts.poll-interval:PT1S}")
    fun poll() {
        if (!settings.enabled || !running.compareAndSet(false, true)) return
        executor.execute {
            try {
                runDue()
            } catch (failure: Exception) {
                log.warn("Artifact worker failed: {}", failure.javaClass.simpleName)
            } finally {
                running.set(false)
            }
        }
    }

    @PreDestroy
    fun close() {
        executor.shutdownNow()
    }

    fun runDue(): Int {
        artifacts.prepareFinalLoans()
        var generated = 0
        repeat(3) {
            if (Thread.currentThread().isInterrupted) return generated
            if (artifacts.generateOne()) generated++
        }
        artifacts.notifyReadyGroups()
        return generated
    }
}
