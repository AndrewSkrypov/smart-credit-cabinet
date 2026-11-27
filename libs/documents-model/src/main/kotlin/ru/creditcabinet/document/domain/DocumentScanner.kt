package ru.creditcabinet.document.domain

/** A scan must finish successfully before bytes or document metadata are saved. */
fun interface DocumentScanner {
    fun scan(content: ByteArray)
}
