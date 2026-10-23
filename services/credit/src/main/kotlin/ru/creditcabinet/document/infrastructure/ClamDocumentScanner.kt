package ru.creditcabinet.document.infrastructure

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.exception.BadRequestException
import ru.creditcabinet.document.domain.DocumentScanner
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Private clamd TCP protocol: INSTREAM with NUL framing, no paths shared with the scanner. */
@Component
class ClamDocumentScanner(
    @Value("\${cabinet.antivirus.enabled:true}") private val enabled: Boolean,
    @Value("\${cabinet.antivirus.host:localhost}") private val host: String,
    @Value("\${cabinet.antivirus.port:3310}") private val port: Int,
    @Value("\${cabinet.antivirus.timeout:PT10S}") private val timeout: Duration,
    private val meters: MeterRegistry,
    @Value("\${cabinet.antivirus.max-signature-age:PT72H}") private val maxSignatureAge: Duration = Duration.ofHours(72),
    private val clock: Clock = Clock.systemUTC(),
) : DocumentScanner {
    init {
        require(host.isNotBlank() && port in 1..65535) { "Проверьте адрес сервиса проверки файлов" }
        require(timeout.toMillis() in 100..120_000) { "Время проверки файлов — от 100 мс до двух минут" }
        require(maxSignatureAge in Duration.ofHours(1)..Duration.ofDays(7)) { "Допустимый возраст сигнатур — от часа до семи дней" }
    }

    override fun scan(content: ByteArray) {
        if (!enabled) return
        val deadlineNanos = System.nanoTime() + timeout.toNanos()
        val reply = try {
            ensureFresh(exchange("zVERSION", null, deadlineNanos))
            exchange("zINSTREAM", content, deadlineNanos)
        } catch (_: IOException) {
            unavailable()
        }
        when {
            reply == "stream: OK" -> meters.counter(METRIC, "outcome", "clean").increment()
            reply.startsWith("stream: Heuristics.Limits.Exceeded") && reply.endsWith(" FOUND") -> unavailable()
            reply.startsWith("stream: Heuristics.") && reply.contains("Encrypted") && reply.endsWith(" FOUND") -> {
                meters.counter(METRIC, "outcome", "uninspectable").increment()
                throw BadRequestException("Файл не удалось проверить полностью. Снимите защиту паролем или выберите другой файл")
            }
            reply.startsWith("stream: ") && reply.endsWith(" FOUND") -> {
                meters.counter(METRIC, "outcome", "infected").increment()
                throw BadRequestException("Файл содержит угрозу и не сохранён. Выберите безопасный файл")
            }
            else -> unavailable()
        }
    }

    private fun ensureFresh(version: String) {
        try {
            if (!version.startsWith("ClamAV ") || version.count { it == '/' } != 2) throw IOException("Unknown scanner version")
            val date = version.substringAfterLast('/').trim().replace(Regex("\\s+"), " ")
            val publishedAt = LocalDateTime.parse(date, SIGNATURE_DATE).toInstant(ZoneOffset.UTC)
            val age = Duration.between(publishedAt, clock.instant())
            if (age > maxSignatureAge || age < Duration.ofMinutes(-5)) throw IOException("Scanner signatures are not current")
        } catch (_: DateTimeParseException) {
            throw IOException("Unknown scanner signature date")
        }
    }

    private fun exchange(command: String, content: ByteArray?, deadlineNanos: Long): String = Socket().use { socket ->
        val remaining = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
        if (remaining <= 0) throw IOException("Scanner deadline exceeded")
        // soTimeout only bounds reads. Closing at a total deadline also interrupts a stalled write.
        val deadline = DEADLINES.schedule({ runCatching { socket.close() } }, remaining, TimeUnit.MILLISECONDS)
        try {
            socket.connect(InetSocketAddress(host, port), minOf(remaining, 2000).toInt())
            socket.soTimeout = remaining.toInt()
            val output = DataOutputStream(socket.getOutputStream())
            output.write("$command\u0000".toByteArray(Charsets.US_ASCII))
            if (content != null) {
                var offset = 0
                while (offset < content.size) {
                    val size = minOf(8192, content.size - offset)
                    output.writeInt(size)
                    output.write(content, offset, size)
                    offset += size
                }
                output.writeInt(0)
            }
            output.flush()
            val input = socket.getInputStream()
            val reply = StringBuilder()
            while (reply.length < 1024) {
                when (val next = input.read()) {
                    0 -> return@use reply.toString()
                    -1 -> throw IOException("Incomplete scanner response")
                    in 32..126 -> reply.append(next.toChar())
                    else -> throw IOException("Invalid scanner response")
                }
            }
            throw IOException("Scanner response too long")
        } finally {
            deadline.cancel(false)
        }
    }

    private fun unavailable(): Nothing {
        meters.counter(METRIC, "outcome", "unavailable").increment()
        throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Проверка файлов временно недоступна. Попробуйте загрузить файл позже")
    }

    private companion object {
        const val METRIC = "cabinet.document.scan"
        val SIGNATURE_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", Locale.US)
        val DEADLINES = Executors.newSingleThreadScheduledExecutor { job -> Thread(job, "document-scan-deadline").apply { isDaemon = true } }
    }
}
