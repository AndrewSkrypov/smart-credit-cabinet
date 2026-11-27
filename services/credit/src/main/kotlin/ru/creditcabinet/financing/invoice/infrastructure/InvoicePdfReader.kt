package ru.creditcabinet.financing.invoice.infrastructure

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.springframework.stereotype.Component
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.financing.invoice.application.InvoicePdfTooLarge
import ru.creditcabinet.financing.invoice.application.InvoiceTextReader
import ru.creditcabinet.financing.invoice.domain.InvoicePdfLine
import java.io.IOException
import java.io.Writer

@Component
internal class InvoicePdfReader : InvoiceTextReader {
    override fun read(bytes: ByteArray): List<InvoicePdfLine> {
        if (!hasPdfHeader(bytes)) throw BadRequestException("Выберите корректный PDF-счёт")
        try {
            return Loader.loadPDF(bytes).use { document ->
                if (document.isEncrypted) throw BadRequestException("Счёт защищён паролем — выберите открытый PDF")
                if (document.numberOfPages !in 1..MAX_PAGES) throw tooLarge()
                val stripper = PDFTextStripper().apply { sortByPosition = true }
                var charactersLeft = MAX_TEXT_CHARS
                (1..document.numberOfPages).flatMap { page ->
                    stripper.startPage = page
                    stripper.endPage = page
                    val writer = LimitedWriter(charactersLeft)
                    stripper.writeText(document, writer)
                    charactersLeft -= writer.text.length
                    writer.text.lineSequence().map { it.trim() }
                        .filter { it.isNotEmpty() && it.length <= MAX_LINE_CHARS }
                        .map { InvoicePdfLine(page, it) }.toList()
                }
            }
        } catch (_: TextTooLarge) {
            throw tooLarge()
        } catch (_: IOException) {
            throw BadRequestException("Не удалось прочитать PDF-счёт. Проверьте файл и попробуйте ещё раз")
        }
    }

    private fun hasPdfHeader(bytes: ByteArray): Boolean =
        bytes.size >= PDF_HEADER.size && PDF_HEADER.indices.all { bytes[it] == PDF_HEADER[it] }

    private fun tooLarge() = InvoicePdfTooLarge("Для разбора выберите PDF-счёт до 4 страниц и 2 МБ")

    private class TextTooLarge : IOException()

    private class LimitedWriter(private val limit: Int) : Writer() {
        val text = StringBuilder()

        override fun write(buffer: CharArray, offset: Int, length: Int) {
            if (length > limit - text.length) throw TextTooLarge()
            text.append(buffer, offset, length)
        }

        override fun flush() = Unit

        override fun close() = Unit
    }

    companion object {
        private const val MAX_PAGES = 4
        private const val MAX_TEXT_CHARS = 24_000
        private const val MAX_LINE_CHARS = 180
        private val PDF_HEADER = "%PDF-".toByteArray(Charsets.US_ASCII)
    }
}
