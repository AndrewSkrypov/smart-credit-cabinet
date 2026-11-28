package ru.creditcabinet.documents

import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.domain.StorageBackend
import ru.creditcabinet.document.domain.StoredFile
import ru.creditcabinet.document.generated.ArtifactInput
import ru.creditcabinet.document.generated.ArtifactPhase
import ru.creditcabinet.documents.application.ArtifactOperations
import ru.creditcabinet.documents.application.ArtifactRecords
import ru.creditcabinet.documents.application.DocumentRecords
import ru.creditcabinet.documents.application.FrozenArtifactInput
import ru.creditcabinet.documents.domain.ArtifactLifecycle
import ru.creditcabinet.documents.domain.ArtifactPublicationState
import ru.creditcabinet.documents.domain.samePreparation
import ru.creditcabinet.product.domain.LoanMath
import java.io.InputStream
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArtifactOperationsTest {
    private val policy = ArtifactLifecycle(2)
    private val fixture = ArtifactFixture()

    @Test
    fun `publication excludes READY published old future and exhausted jobs but admits pending and error`() {
        for (status in listOf("PENDING", "ERROR")) {
            for (attempt in 0..4) assertTrue(policy.eligible(ArtifactPublicationState(2, status, false, false, attempt)))
        }
        for (state in listOf(
            ArtifactPublicationState(2, "READY", false, false, 0),
            ArtifactPublicationState(2, "ERROR", true, false, 0),
            ArtifactPublicationState(2, "ERROR", false, true, 0),
            ArtifactPublicationState(1, "PENDING", false, false, 0),
            ArtifactPublicationState(3, "PENDING", false, false, 0),
            ArtifactPublicationState(2, "PENDING", false, false, 5),
        )) {
            assertFalse(policy.eligible(state))
        }
        assertEquals(60L, policy.leaseSeconds)
        assertEquals(15L, policy.failureRetrySeconds)
    }

    @Test
    fun `READY is returned unchanged before retiring a lease even for an old or future template`() {
        for (version in 1..3) {
            val record = fixture.record.copy(templateVersion = version, status = "READY", checksum = "published", readyAt = Instant.EPOCH)
            val repository = ArtifactMemory(record)
            assertEquals(record, operations(repository).retry(record.scope, record.id))
            assertEquals(listOf("scope", "lock"), repository.events)
        }
    }

    @Test
    fun `current published metadata cannot be retried even if its status is ERROR`() {
        for (record in listOf(fixture.record.copy(checksum = "published"), fixture.record.copy(readyAt = Instant.EPOCH))) {
            val repository = ArtifactMemory(record)
            assertFailsWith<DocumentConflict> { operations(repository).retry(record.scope, record.id) }
            assertEquals(listOf("scope", "lock"), repository.events)
        }
    }

    @Test
    fun `current retry retires token before resetting pending without decoding or replacing frozen metadata`() {
        val old = fixture.record.copy(status = "ERROR")
        val repository = ArtifactMemory(old)
        val result = operations(repository, FrozenArtifactInput { error("current retry must not decode") }).retry(old.scope, old.id)
        assertEquals(old.copy(status = "PENDING"), result)
        assertEquals(listOf("scope", "lock", "retire", "pending"), repository.events)
        assertEquals(result, repository.rows.single())
    }

    @Test
    fun `live lease blocks retry before replacement allocation or future version validation`() {
        for (version in 1..3) {
            val old = fixture.record.copy(templateVersion = version)
            val repository = ArtifactMemory(old).apply { leaseAvailable = false }
            val operations = operations(repository, newId = { error("must not allocate before lease retirement") })
            assertFailsWith<IllegalStateException> { operations.retry(old.scope, old.id) }
            assertEquals(listOf("scope", "lock", "retire"), repository.events)
            assertEquals(old, repository.rows.single())
        }
    }

    @Test
    fun `future nonready template is rejected after lease retirement and never rewritten`() {
        val old = fixture.record.copy(templateVersion = 3)
        val repository = ArtifactMemory(old)
        assertFailsWith<IllegalArgumentException> { operations(repository).retry(old.scope, old.id) }
        assertEquals(listOf("scope", "lock", "retire"), repository.events)
        assertEquals(old, repository.rows.single())
    }

    @Test
    fun `old template retry preserves frozen final snapshot and backend while deduplicating its current replacement`() {
        val old = fixture.record.copy(templateVersion = 1, status = "ERROR", checksum = "old", readyAt = Instant.EPOCH, sizeBytes = 42)
        val repository = ArtifactMemory(old)
        val first = operations(repository).retry(old.scope, old.id)
        val replay = operations(repository).retry(old.scope, old.id)
        assertEquals(first, replay)
        assertEquals(old, repository.rows.first())
        assertEquals(2, repository.rows.size)
        assertEquals(
            old.copy(
                id = first.id,
                storedName = "generated-${first.id}.pdf",
                templateVersion = 2,
                status = "PENDING",
                sizeBytes = null,
                checksum = null,
                readyAt = null,
            ),
            first,
        )
        assertEquals(old.inputJson, first.inputJson)
        assertEquals("S3", first.backend)
        assertEquals("FINAL", first.phase)
    }

    @Test
    fun `prepare replays original identity by full key while another frozen hash remains distinct`() {
        val repository = ArtifactMemory()
        val operations = operations(repository)
        val first = operations.prepare(fixture.record)
        assertEquals(first, operations.prepare(first.copy(id = UUID.randomUUID()).let { it.copy(storedName = "generated-${it.id}.pdf") }))
        val changedJson = fixture.json + " "
        val another = first.copy(id = UUID.randomUUID(), inputJson = changedJson, inputHash = sha256(changedJson.toByteArray()))
            .let { it.copy(storedName = "generated-${it.id}.pdf") }
        assertEquals(another, operations.prepare(another))
        assertEquals(2, repository.rows.size)
    }

    @Test
    fun `prepare rejects altered frozen bytes and invalid scope before metadata publication`() {
        val repository = ArtifactMemory()
        assertFailsWith<IllegalArgumentException> { operations(repository).prepare(fixture.record.copy(inputJson = fixture.json + "changed")) }
        assertTrue(repository.rows.isEmpty())
        repository.scopeAllowed = false
        assertFailsWith<IllegalArgumentException> { operations(repository).prepare(fixture.record) }
        assertTrue(repository.rows.isEmpty())
    }

    @Test
    fun `artifact read requires READY and exact backend size and checksum`() {
        val repository = ArtifactMemory(fixture.record)
        val files = ArtifactFiles()
        val operations = operations(repository, files = files)
        assertFailsWith<IllegalStateException> { operations.bytes(fixture.record.scope, fixture.record.id) }
        assertEquals(0, files.reads)
        val bytes = "published PDF".toByteArray()
        files.bytes = bytes
        repository.rows[0] = fixture.record.copy(status = "READY", sizeBytes = bytes.size.toLong(), checksum = sha256(bytes))
        assertContentEquals(bytes, operations.bytes(fixture.record.scope, fixture.record.id))
        assertEquals(StorageBackend.S3, files.backend)
        files.bytes = "different PDF".toByteArray()
        assertFailsWith<IllegalStateException> { operations.bytes(fixture.record.scope, fixture.record.id) }
    }

    private fun operations(
        repository: ArtifactMemory,
        decoder: FrozenArtifactInput = FrozenArtifactInput { fixture.input },
        newId: () -> UUID = UUID::randomUUID,
        files: FileStore = ArtifactFiles(),
    ) = ArtifactOperations(repository, repository, decoder, files, policy, newId)
}

internal class ArtifactFixture {
    val scope = DocumentScope(UUID.randomUUID(), UUID.randomUUID())
    val input = ArtifactInput(
        scope.applicationId, 123, UUID.randomUUID(), "Frozen borrower", "1234567890", "Frozen product", 123456, 12,
        LoanMath.calculate(123456, BigDecimal("12.00"), 12, false), ArtifactPhase.FINAL,
        "Frozen signed terms", sha256("Frozen signed terms".toByteArray()), false, Instant.EPOCH, "C-123", Instant.EPOCH,
    )

    // The pure decoder port supplies the typed snapshot; JSON serialization is adapter-owned.
    val json = "opaque immutable serialized snapshot"
    val record = UUID.randomUUID().let {
        ArtifactRecord(
            it, scope, input.offerId, "CONTRACT_PDF", "FINAL", 2, sha256(json.toByteArray()), json,
            "Contract", "contract.pdf", "application/pdf", "generated-$it.pdf", "S3", Instant.EPOCH,
        )
    }
}

internal class ArtifactMemory(vararg initial: ArtifactRecord) : DocumentRecords, ArtifactRecords {
    val rows = initial.toMutableList()
    val events = mutableListOf<String>()
    var leaseAvailable = true
    var scopeAllowed = true
    override fun verifyScope(scope: DocumentScope, bind: Boolean) {
        events += "scope"
        require(scopeAllowed)
    }
    override fun documents(scope: DocumentScope) = emptyList<DocumentRecord>()
    override fun saveDeleted(record: DocumentRecord) = error("unused")
    override fun artifacts(scope: DocumentScope) = rows.filter { it.scope == scope }
    override fun insertArtifact(record: ArtifactRecord) {
        events += "insert"
        if (rows.none { it.samePreparation(record) }) rows += record
    }
    override fun lockArtifact(scope: DocumentScope, id: UUID): ArtifactRecord? {
        events += "lock"
        return rows.firstOrNull { it.scope == scope && it.id == id }
    }
    override fun retireExpiredLease(id: UUID): Boolean {
        events += "retire"
        return leaseAvailable
    }
    override fun resetPending(record: ArtifactRecord): Boolean {
        events += "pending"
        rows[rows.indexOfFirst { it.id == record.id }] = record
        return true
    }
}

internal class ArtifactFiles : FileStore {
    var bytes: ByteArray? = null
    var backend: StorageBackend? = null
    var reads = 0
    var writes = 0
    override fun put(storedName: String, content: InputStream, sizeBytes: Long) = error("backend must be explicit")
    override fun get(storedName: String): StoredFile? = error("backend must be explicit")
    override fun put(storedName: String, content: InputStream, sizeBytes: Long, backend: StorageBackend) {
        this.backend = backend
        bytes = content.readBytes()
        writes++
    }
    override fun get(storedName: String, backend: StorageBackend): StoredFile? {
        this.backend = backend
        reads++
        return bytes?.let { StoredFile(it.size.toLong(), it.inputStream()) }
    }
}
