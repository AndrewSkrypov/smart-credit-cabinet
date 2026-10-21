package ru.creditcabinet.document.domain

/**
 * Whether a file's bytes are what its extension claims. Downloads are always served as
 * attachments, so this is not about scripts in the browser: it keeps an executable or an
 * archive from travelling under a harmless name like "report.pdf".
 */
object FileContent {
    private val PDF = "%PDF-".toByteArray()
    private val PNG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val GIF = "GIF8".toByteArray()
    private val ZIP = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val ZIP_EMPTY = byteArrayOf(0x50, 0x4B, 0x05, 0x06)

    // Word, Excel and PowerPoint 97-2003 are OLE compound files.
    private val OLE = byteArrayOf(0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(), 0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte())

    private val TEXT = setOf("txt", "md", "csv")

    fun matches(extension: String, bytes: ByteArray): Boolean =
        when (extension) {
            "pdf" -> bytes.startsWith(PDF)
            "png" -> bytes.startsWith(PNG)
            "jpg", "jpeg" -> bytes.startsWith(JPEG)
            "gif" -> bytes.startsWith(GIF)
            // docx, xlsx and pptx are zip archives.
            "zip", "docx", "xlsx", "pptx" -> bytes.startsWith(ZIP) || bytes.startsWith(ZIP_EMPTY)
            "doc", "xls", "ppt" -> bytes.startsWith(OLE)
            "svg" -> isText(bytes) && String(bytes, Charsets.UTF_8).contains("<svg", ignoreCase = true)
            in TEXT -> isText(bytes)
            else -> false
        }

    /** UTF-8 text without NUL bytes: what a .txt, .md, .csv or .svg really is. */
    private fun isText(bytes: ByteArray): Boolean {
        if (bytes.any { it == 0.toByte() }) return false
        val decoder = Charsets.UTF_8.newDecoder()
        return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes)) }.isSuccess
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
