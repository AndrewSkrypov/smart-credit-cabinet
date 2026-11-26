package ru.creditcabinet.catalog.application

import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import ru.creditcabinet.catalog.domain.CatalogEditor
import ru.creditcabinet.catalog.domain.CatalogFenced
import ru.creditcabinet.catalog.domain.CatalogReader
import ru.creditcabinet.catalog.domain.CatalogWriter
import ru.creditcabinet.product.domain.FinancingPurpose
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductLifecycleStatus
import ru.creditcabinet.product.domain.ProductRevision
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CatalogApplicationTest {
    private val reader = mock<CatalogReader>()
    private val writer = mock<CatalogWriter>()
    private val at = Instant.parse("2026-11-27T09:00:00Z")
    private val clock = Clock.fixed(at, ZoneOffset.UTC)
    private val editor = CatalogEditor(UUID.randomUUID(), "Риск-администратор")
    private val candidate = Product(
        code = "WORKING_CAPITAL",
        name = "Оборотный кредит",
        description = "Синтетические условия",
        purposes = setOf(FinancingPurpose.WORKING_CAPITAL),
        minAmount = 300_000,
        maxAmount = 5_000_000,
        minTermMonths = 3,
        maxTermMonths = 24,
        baseRate = BigDecimal("20.25"),
        autoApprovalLimit = 1_000_000,
        revolving = false,
        version = 4,
        lifecycleStatus = ProductLifecycleStatus.ACTIVE,
    )

    @Test
    fun `fence blocks current operations but exact history still uses the reader port`() {
        val catalog = CatalogUseCases(reader, writer, { throw CatalogFenced() }, clock)
        assertFailsWith<CatalogFenced> { catalog.current(candidate.code, effectiveOnly = true) }
        assertFailsWith<CatalogFenced> { catalog.snapshotForSubmission(candidate.code) }
        verifyNoInteractions(reader, writer)
        val revision = ProductRevision(candidate, editor.id, "Аудит", at)
        whenever(reader.version(candidate.code, candidate.version)).thenReturn(revision)
        assertEquals(revision, catalog.version(candidate.code, candidate.version))
        verify(reader).version(candidate.code, candidate.version)
    }

    @Test
    fun `write validates before the port and forwards the editor and expected version`() {
        val catalog = CatalogUseCases(reader, writer, {}, clock)
        assertFailsWith<IllegalArgumentException> { catalog.create(candidate, editor, "Активный продукт") }
        assertFailsWith<IllegalArgumentException> { catalog.replace(candidate, 3, editor, "Неверная версия") }
        assertFailsWith<IllegalArgumentException> { catalog.replace(candidate, 4, editor, " ") }
        verifyNoInteractions(reader, writer)

        val stored = ProductRevision(candidate.copy(version = 5), editor.id, "Изменение условий", at)
        whenever(writer.replace(candidate, 4, editor, stored.reason, at)).thenReturn(stored)
        assertEquals(stored, catalog.replace(candidate, 4, editor, "  Изменение условий "))
        verify(writer).replace(candidate, 4, editor, stored.reason, at)
    }

    @Test
    fun `application and domain do not import transport or persistence adapters`() {
        val roots = listOf(
            Path.of("src/main/kotlin/ru/creditcabinet/catalog/application"),
            Path.of("src/main/kotlin/ru/creditcabinet/catalog/domain"),
            Path.of("../../libs/catalog-model/src/main/kotlin"),
        )
        val forbidden = listOf(
            "java.sql.", "javax.sql.", "org.postgresql.", "org.springframework.jdbc.", "org.springframework.transaction.",
            "io.grpc.", "com.google.protobuf.", "ru.creditcabinet.contracts.", "ru.creditcabinet.cataloggrpc.",
            "ru.creditcabinet.catalogjdbc.", "ru.creditcabinet.catalog.infrastructure.",
        )
        for (root in roots) {
            val sources = Files.walk(root).use { paths -> paths.filter { it.toString().endsWith(".kt") }.toList() }
            assertTrue(sources.isNotEmpty(), "No Kotlin sources under $root")
            for (source in sources) {
                Files.readAllLines(source).map(String::trim).filter { it.startsWith("import ") }
                    .map { it.removePrefix("import ") }.forEach { imported ->
                        assertTrue(forbidden.none(imported::startsWith), "$source imports $imported")
                    }
            }
        }
    }
}
