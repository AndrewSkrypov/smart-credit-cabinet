package ru.creditcabinet.catalog.infrastructure

import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.TransactionException
import org.springframework.transaction.support.TransactionTemplate
import ru.creditcabinet.catalog.domain.CatalogAuthority
import ru.creditcabinet.catalog.domain.CatalogEditor
import ru.creditcabinet.catalog.domain.CatalogFenced
import ru.creditcabinet.catalog.domain.CatalogReader
import ru.creditcabinet.catalog.domain.CatalogUnavailable
import ru.creditcabinet.catalog.domain.CatalogWriter
import ru.creditcabinet.catalogjdbc.JdbcProductCatalog
import ru.creditcabinet.common.security.Roles
import ru.creditcabinet.product.domain.Product
import ru.creditcabinet.product.domain.ProductRevision
import java.time.Instant

internal class JdbcCatalogAuthority(private val jdbc: JdbcTemplate) : CatalogAuthority {
    override fun requireCurrent() {
        val enabled = database { jdbc.queryForObject("select writable from catalog_cutover.authority where singleton", Boolean::class.java) }
        if (enabled != true) throw CatalogFenced()
    }
}

internal class JdbcCatalogReader(private val catalog: JdbcProductCatalog) : CatalogReader {
    override fun snapshotForSubmission(selectedProductCode: String) = database { catalog.snapshotForSubmission(selectedProductCode) }
    override fun current(code: String?, effectiveAt: Instant?) = database { catalog.currentRevisions(code, effectiveAt) }
    override fun version(code: String, version: Long) = database { catalog.revision(code, version) }
    override fun history(code: String) = database { catalog.history(code) }
}

internal class TransactionalCatalogWriter(
    private val catalog: JdbcProductCatalog,
    private val authority: CatalogAuthority,
    private val transactions: TransactionTemplate,
) : CatalogWriter {
    override fun create(product: Product, editor: CatalogEditor, reason: String, at: Instant): ProductRevision = write {
        catalog.create(product, editor.id, reason, at, editor.name, Roles.RISK_ADMIN)
    }

    override fun replace(product: Product, expectedVersion: Long, editor: CatalogEditor, reason: String, at: Instant): ProductRevision = write {
        catalog.replace(product, expectedVersion, editor.id, reason, at, editor.name, Roles.RISK_ADMIN)
    }

    private fun write(save: () -> ProductRevision): ProductRevision = database {
        checkNotNull(
            transactions.execute {
                authority.requireCurrent()
                save()
            },
        )
    }
}

private fun <T> database(action: () -> T): T = try {
    action()
} catch (failure: DataAccessException) {
    throw CatalogUnavailable(failure)
} catch (failure: TransactionException) {
    throw CatalogUnavailable(failure)
}
