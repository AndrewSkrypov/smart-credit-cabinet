package ru.creditcabinet.document

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** EICAR is built in memory; no infected fixture is written to the host filesystem. */
object DocumentScanFixtures {
    fun infectedDocx(): ByteArray {
        val signature = listOf("X5O!P%@AP[4", "\\PZX54(P^)7CC)7}", "$" + "EICAR", "-STANDARD-ANTIVIRUS-TEST-FILE!", "$" + "H+H*").joinToString("")
        check(signature.length == 68)
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            for ((name, content) in listOf(
                "[Content_Types].xml" to
                    """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/></Types>""",
                "word/document.xml" to """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body/></w:document>""",
                "sample.com" to signature,
            )) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
