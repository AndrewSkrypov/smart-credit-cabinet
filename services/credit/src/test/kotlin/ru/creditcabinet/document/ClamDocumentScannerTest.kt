package ru.creditcabinet.document

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.springframework.http.HttpStatus
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.document.domain.FileContent
import ru.creditcabinet.document.infrastructure.ClamDocumentScanner
import java.io.DataInputStream
import java.net.ServerSocket
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ClamDocumentScannerTest {
    private fun scanner(port: Int, timeout: Duration = Duration.ofSeconds(10)) =
        ClamDocumentScanner(
            true,
            "127.0.0.1",
            port,
            timeout,
            SimpleMeterRegistry(),
            maxSignatureAge = Duration.parse(System.getenv("ANTIVIRUS_MAX_SIGNATURE_AGE") ?: "PT72H"),
        )

    @Test
    fun `real clamd accepts a clean PDF and finds EICAR inside an allowed archive`() {
        val scanner = scanner(System.getenv("AV_TEST_PORT")?.toInt() ?: 33102)
        scanner.scan("%PDF-1.7\n% synthetic\n%%EOF".toByteArray())
        val infected = DocumentScanFixtures.infectedDocx()
        assertTrue(FileContent.matches("docx", infected), "The infected upload must pass the existing signature gate")
        val error = assertFailsWith<ApiException> { scanner.scan(infected) }
        assertEquals(HttpStatus.BAD_REQUEST, error.httpStatus)
        assertTrue(error.message!!.contains("содержит угрозу"))
    }

    @Test
    fun `closed port never permits an unscanned file`() {
        val port = ServerSocket(0).use { it.localPort }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertFailsWith<ApiException> { scanner(port).scan(ByteArray(4)) }.httpStatus)
    }

    @Test
    fun `real clamd does not mark an archive clean after exceeding its inspection limit`() {
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            repeat(1002) { index ->
                zip.putNextEntry(java.util.zip.ZipEntry("file-$index.txt"))
                zip.write("Synthetic content $index".toByteArray())
                zip.closeEntry()
            }
        }
        val error = assertFailsWith<ApiException> { scanner(System.getenv("AV_TEST_PORT")?.toInt() ?: 33102).scan(bytes.toByteArray()) }
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.httpStatus)
        assertTrue(error.message!!.contains("Проверка файлов временно недоступна"))
    }

    @Test
    fun `unknown scanner reply fails closed`() {
        withServer({ socket ->
            socket.getInputStream().readNBytes(10)
            socket.getOutputStream().write("stream: UNKNOWN\u0000".toByteArray())
        }) { port ->
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertFailsWith<ApiException> { scanner(port).scan(ByteArray(4)) }.httpStatus)
        }
    }

    @Test
    fun `the total deadline interrupts a scanner which accepts but never reads the stream`() {
        withServer({ socket ->
            while (socket.getInputStream().read() != -1) Thread.sleep(5)
        }) { port ->
            val start = System.nanoTime()
            assertEquals(
                HttpStatus.SERVICE_UNAVAILABLE,
                assertFailsWith<ApiException> {
                    scanner(port, Duration.ofMillis(150)).scan(ByteArray(20 * 1024 * 1024))
                }.httpStatus,
            )
            assertTrue(Duration.ofNanos(System.nanoTime() - start) < Duration.ofSeconds(3))
        }
    }

    @Test
    fun `the default rejects four-day signatures and the explicit Dev policy rejects signatures older than seven days`() {
        val clock = Clock.fixed(Instant.parse("2026-11-28T12:00:00Z"), ZoneOffset.UTC)
        fun scannerAt(port: Int, age: Duration) =
            ClamDocumentScanner(true, "127.0.0.1", port, Duration.ofSeconds(3), SimpleMeterRegistry(), maxSignatureAge = age, clock = clock)

        val defaultStreamed = java.util.concurrent.atomic.AtomicBoolean(false)
        withServer({ defaultStreamed.set(true) }, "ClamAV 1.5.5/28194/Tue Nov 24 12:00:00 2026") { port ->
            val defaultScanner = ClamDocumentScanner(true, "127.0.0.1", port, Duration.ofSeconds(3), SimpleMeterRegistry(), clock = clock)
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertFailsWith<ApiException> { defaultScanner.scan(ByteArray(4)) }.httpStatus)
        }
        assertTrue(!defaultStreamed.get(), "The ordinary default must reject four-day signatures before sending content")

        withServer({ socket ->
            val input = DataInputStream(socket.getInputStream())
            assertEquals("zINSTREAM\u0000", String(input.readNBytes(10), Charsets.US_ASCII))
            assertEquals(4, input.readInt())
            assertEquals(4, input.readNBytes(4).size)
            assertEquals(0, input.readInt())
            socket.getOutputStream().write("stream: OK\u0000".toByteArray(Charsets.US_ASCII))
        }, "ClamAV 1.5.5/28194/Tue Nov 24 12:00:00 2026") { port ->
            scannerAt(port, Duration.ofDays(7)).scan(ByteArray(4))
        }

        val streamed = java.util.concurrent.atomic.AtomicBoolean(false)
        withServer({ streamed.set(true) }, "ClamAV 1.5.5/28194/Sat Nov 21 11:59:59 2026") { port ->
            val failure = assertFailsWith<ApiException> { scannerAt(port, Duration.ofDays(7)).scan(ByteArray(4)) }
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, failure.httpStatus)
        }
        assertTrue(!streamed.get(), "A stale scanner must receive no document content")
    }

    @Test
    fun `stale future and malformed signatures fail closed before any content is sent`() {
        val dates = listOf(
            "ClamAV 1.5.4/1/Mon Jan 1 00:00:00 2024",
            "ClamAV 1.5.4/1/Sat Jan 1 00:00:00 2050",
            "ClamAV 1.5.4/1/unknown date",
            "unknown scanner",
        )
        for (version in dates) {
            val streamed = java.util.concurrent.atomic.AtomicBoolean(false)
            withServer({ streamed.set(true) }, version) { port ->
                assertEquals(HttpStatus.SERVICE_UNAVAILABLE, assertFailsWith<ApiException> { scanner(port).scan(ByteArray(4)) }.httpStatus)
            }
            assertTrue(!streamed.get(), "A scanner without current signatures must receive no document content")
        }
    }

    private fun withServer(
        serve: (java.net.Socket) -> Unit,
        version: String = "ClamAV 1.5.4/1/" + java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", java.util.Locale.US)),
        check: (Int) -> Unit,
    ) {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val accepted = java.util.concurrent.atomic.AtomicReference<java.net.Socket>()
            executor.submit {
                runCatching {
                    server.accept().use {
                        accepted.set(it)
                        assertEquals("zVERSION\u0000", String(it.getInputStream().readNBytes(9), Charsets.US_ASCII))
                        it.getOutputStream().write("$version\u0000".toByteArray(Charsets.US_ASCII))
                    }
                    server.accept().use {
                        accepted.set(it)
                        serve(it)
                    }
                }
            }
            try {
                check(server.localPort)
            } finally {
                accepted.get()?.close()
                server.close()
                executor.shutdownNow()
                assertTrue(executor.awaitTermination(3, TimeUnit.SECONDS))
            }
        }
    }
}
