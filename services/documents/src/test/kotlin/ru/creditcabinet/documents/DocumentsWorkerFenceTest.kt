package ru.creditcabinet.documents

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.SimpleTransactionStatus
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.document.domain.DocumentScanner
import ru.creditcabinet.document.domain.FileStore
import ru.creditcabinet.document.generated.ArtifactInput
import ru.creditcabinet.document.generated.ArtifactPhase
import ru.creditcabinet.document.generated.ArtifactRenderer
import ru.creditcabinet.product.domain.LoanMath
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class DocumentsWorkerFenceTest {
    @Test
    fun `failed workers cannot publish ERROR after freeze or a new epoch`() {
        for (next in listOf("FROZEN" to 2L, "DOCUMENTS" to 2L, "DOCUMENTS" to 1L)) {
            val mapper = jacksonObjectMapper().findAndRegisterModules()
            val jdbc = Mockito.mock(JdbcTemplate::class.java)
            val tx = Mockito.mock(TransactionTemplate::class.java)
            val renderer = Mockito.mock(ArtifactRenderer::class.java)
            var gate = mapOf<String, Any>("owner" to "DOCUMENTS", "epoch" to 1L)
            Mockito.`when`(jdbc.queryForMap(anyString())).thenAnswer { gate }
            Mockito.`when`(tx.execute(any<TransactionCallback<Any>>())).thenAnswer { invocation ->
                invocation.getArgument<TransactionCallback<Any>>(0).doInTransaction(SimpleTransactionStatus())
            }
            val scope = DocumentScope(UUID.randomUUID(), UUID.randomUUID())
            val input = ArtifactInput(
                scope.applicationId, 1, UUID.randomUUID(), "Снимок", "000100000004", "Продукт", 1000, 1,
                LoanMath.calculate(1000, BigDecimal("12.00"), 1, false), ArtifactPhase.FINAL, "Условия", null, true, null, "proof", Instant.EPOCH,
            )
            val frozen = mapper.writeValueAsString(input)
            val record = ArtifactRecord(
                UUID.randomUUID(), scope, input.offerId, "CONTRACT_PDF", "FINAL", 2, sha256(frozen.toByteArray()), frozen,
                "Условия", "contract.pdf", "application/pdf", "generated-${UUID.randomUUID()}.pdf", "LOCAL", Instant.EPOCH,
            )
            Mockito.`when`(jdbc.queryForList(anyString())).thenReturn(listOf(mapOf("id" to record.id, "metadata" to mapper.writeValueAsString(record))))
            Mockito.doAnswer {
                gate = mapOf("owner" to next.first, "epoch" to next.second)
                throw java.io.IOException("Synthetic renderer failure")
            }.`when`(renderer).render(any(), any())
            val store = DocumentsStore(jdbc, mapper, tx, Mockito.mock(FileStore::class.java), DocumentScanner {}, renderer, JdbcDocumentMetadata(jdbc, mapper))
            store.generateOne()
            val errorWrites = Mockito.mockingDetails(jdbc).invocations.filter {
                it.method.name == "update" &&
                    it.arguments.first().toString().contains("status='ERROR'")
            }
            assertEquals(if (next == ("DOCUMENTS" to 1L)) 1 else 0, errorWrites.size)
            errorWrites.forEach { invocation ->
                val sql = invocation.arguments.first().toString()
                kotlin.test.assertTrue(sql.contains("lease_until>now()") && sql.contains("status<>'READY'"))
            }
        }
    }
}
