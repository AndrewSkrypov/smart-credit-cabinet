package ru.creditcabinet.documents

import ru.creditcabinet.documents.application.ArtifactClaim
import ru.creditcabinet.documents.application.ArtifactJobs
import ru.creditcabinet.documents.application.FrozenArtifactInput
import ru.creditcabinet.documents.application.GenerateArtifact
import ru.creditcabinet.documents.application.RenderArtifact
import ru.creditcabinet.documents.domain.ArtifactLifecycle
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GenerateArtifactTest {
    private val fixture = ArtifactFixture()
    private val policy = ArtifactLifecycle(2)
    private val files = ArtifactFiles()
    private val now = Instant.parse("2026-11-27T05:00:00Z")
    private val bytes = "%PDF-1.4\nfrozen\n%%EOF".toByteArray()

    @Test
    fun `generation renders the frozen input and publishes bytes before READY under claim callback`() {
        val jobs = Jobs(fixture.record)
        val generation = generation(
            jobs,
            renderer = RenderArtifact { kind, input ->
                assertEquals(fixture.input, input)
                assertEquals(fixture.record.kind, kind.name)
                bytes
            },
        )
        jobs.beforeReady = { assertEquals(1, files.writes) }
        generation.execute()
        assertEquals(policy.ready(fixture.record, bytes, now), jobs.ready)
        assertEquals(1, jobs.publications)
        assertEquals(0, jobs.failures)
        assertEquals("S3", files.backend!!.name)
        assertEquals(fixture.record.inputJson, jobs.ready!!.inputJson)
    }

    @Test
    fun `no claim performs no decoding rendering or storage access`() {
        val jobs = Jobs(null)
        generation(jobs, decoder = FrozenArtifactInput { error("must not decode") }).execute()
        assertEquals(0, files.writes)
        assertEquals(0, jobs.publications)
        assertEquals(0, jobs.failures)
    }

    @Test
    fun `changed frozen input fails before render and uses the existing bounded retry delay`() {
        val jobs = Jobs(fixture.record.copy(inputJson = "changed terms"))
        generation(jobs, renderer = RenderArtifact { _, _ -> error("must not render altered terms") }).execute()
        assertNull(jobs.ready)
        assertEquals(0, files.writes)
        assertEquals(1, jobs.failures)
        assertEquals(15L, jobs.retrySeconds)
    }

    @Test
    fun `empty and oversized rendering never writes an object or publishes READY`() {
        for (size in listOf(0, MAX_DOCUMENT_BYTES + 1)) {
            val jobs = Jobs(fixture.record)
            generation(jobs, renderer = RenderArtifact { _, _ -> ByteArray(size) }).execute()
            assertEquals(1, jobs.failures)
            assertNull(jobs.ready)
        }
        assertEquals(0, files.writes)
    }

    @Test
    fun `a retired claim never invokes object storage or the READY factory`() {
        val jobs = Jobs(fixture.record).apply { active = false }
        generation(jobs).execute()
        assertEquals(0, files.writes)
        assertNull(jobs.ready)
        assertEquals(0, jobs.failures)
    }

    @Test
    fun `ownership loss on publication cannot fall through to an unfenced error write`() {
        val jobs = Jobs(fixture.record).apply { owned = false }
        generation(jobs).execute()
        assertEquals(0, files.writes)
        assertNull(jobs.ready)
        assertEquals(0, jobs.failures)
    }

    @Test
    fun `render failure uses the original claim identity without modifying frozen metadata`() {
        val jobs = Jobs(fixture.record)
        generation(jobs, renderer = RenderArtifact { _, _ -> throw IllegalStateException("render unavailable") }).execute()
        assertEquals(1, jobs.failures)
        assertEquals(fixture.record, jobs.failedClaim!!.record)
        assertEquals(jobs.claim!!.token, jobs.failedClaim!!.token)
        assertEquals(jobs.claim!!.epoch, jobs.failedClaim!!.epoch)
    }

    private fun generation(
        jobs: Jobs,
        renderer: RenderArtifact = RenderArtifact { _, _ -> bytes },
        decoder: FrozenArtifactInput = FrozenArtifactInput { fixture.input },
    ) = GenerateArtifact(jobs, decoder, renderer, files, policy, Clock.fixed(now, ZoneOffset.UTC))

    private class Jobs(record: ArtifactRecord?) : ArtifactJobs {
        val claim = record?.let { ArtifactClaim(it, UUID.randomUUID(), 7L) }
        var owned = true
        var active = true
        var publications = 0
        var failures = 0
        var ready: ArtifactRecord? = null
        var retrySeconds: Long? = null
        var failedClaim: ArtifactClaim? = null
        var beforeReady: () -> Unit = {}
        override fun claim(policy: ArtifactLifecycle): ArtifactClaim? {
            assertEquals(2, policy.templateVersion)
            assertEquals(5, policy.maxAttempts)
            return claim
        }
        override fun publish(claim: ArtifactClaim, produceReady: () -> ArtifactRecord) {
            check(owned)
            if (!active) return
            publications++
            ready = produceReady()
            beforeReady()
        }
        override fun fail(claim: ArtifactClaim, retrySeconds: Long) {
            check(owned)
            assertTrue(active)
            failedClaim = claim
            this.retrySeconds = retrySeconds
            failures++
        }
    }
}
