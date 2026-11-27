package ru.creditcabinet.financing.invoice.application

import ru.creditcabinet.financing.invoice.domain.InvoicePdfLine

fun interface InvoiceTextReader {
    fun read(bytes: ByteArray): List<InvoicePdfLine>

    companion object {
        const val MAX_FILE_BYTES = 2L * 1024 * 1024
    }
}
