package ru.creditcabinet.document.generated

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.poi.ss.usermodel.FillPatternType
import org.apache.poi.ss.usermodel.HorizontalAlignment
import org.apache.poi.ss.usermodel.IndexedColors
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.ss.util.CellRangeAddress
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Component
import java.awt.Color
import java.io.ByteArrayOutputStream
import java.text.NumberFormat
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.GregorianCalendar
import java.util.Locale

@Component
class ArtifactRenderer {
    fun title(kind: ArtifactKind, input: ArtifactInput): String = when (kind) {
        ArtifactKind.CONTRACT_PDF -> when {
            input.legacySummary -> "Сводка сохранённых условий"
            input.phase == ArtifactPhase.PREVIEW -> "Предварительная копия условий договора"
            else -> "Кредитный договор - демонстрационная копия"
        }
        else -> if (input.phase == ArtifactPhase.PREVIEW) "Предварительный график платежей" else "График платежей"
    }

    fun render(kind: ArtifactKind, input: ArtifactInput): ByteArray = when (kind) {
        ArtifactKind.SCHEDULE_XLSX -> xlsx(input)
        else -> pdf(kind, input)
    }

    private fun pdf(kind: ArtifactKind, input: ArtifactInput): ByteArray = PDDocument().use { document ->
        document.documentCatalog.language = "ru-RU"
        document.documentInformation.apply {
            title = title(kind, input)
            author = "Кабинет предпринимателя"
            creationDate = GregorianCalendar.from(input.sourceCreatedAt.atZone(ZoneOffset.UTC))
            modificationDate = creationDate
            input.contractTextHash?.let { setCustomMetadataValue("CabinetContractTextSHA256", it) }
        }
        val regular = font(document, "DejaVuSans.ttf")
        val bold = font(document, "DejaVuSans-Bold.ttf")
        val heading = when {
            kind != ArtifactKind.CONTRACT_PDF -> "График платежей"
            input.legacySummary -> "Сводка сохранённых условий"
            else -> "Кредитный договор"
        }
        val layout = PdfLayout(document, regular, bold, "Заявка № ${input.applicationNumber}", heading)
        layout.paragraph(heading.uppercase(Locale.forLanguageTag("ru-RU")), 17f, true)
        layout.paragraph(
            when {
                input.legacySummary -> "Справочная сводка • исходный подписанный текст не сохранён"
                input.phase == ArtifactPhase.PREVIEW -> "Предварительный расчёт по предложению"
                else -> "По сохранённым условиям выданного кредита"
            },
            9f,
        )
        layout.space(12f)
        if (layout.needsUnicodeNotation(listOf(input.companyName, input.productName, input.contractText))) {
            layout.notice(
                "Часть символов не представлена встроенным шрифтом и показана как [U+XXXX] - код Unicode. " +
                    "Это печатное представление, а не подписанный оригинал. Сохранённый текст условий и его SHA-256 не изменены; исходный текст доступен в кабинете.",
            )
            document.documentInformation.setCustomMetadataValue(
                "CabinetUnicodeRepresentation",
                "Unsupported glyphs use explicit [U+XXXX] notation; source text unchanged",
            )
        }
        layout.fields(
            listOf(
                "Договор" to (input.contractNumber ?: "До выдачи кредита"),
                "Заявка" to "№ ${input.applicationNumber}",
                "Заёмщик" to input.companyName,
                "ИНН" to input.inn,
                "Кредитный продукт" to input.productName,
            ),
        )
        layout.space(14f)
        if (kind == ArtifactKind.CONTRACT_PDF) {
            layout.section("01", if (input.legacySummary) "Сводка условий кредита" else "Сохранённый текст условий")
            layout.paragraph(input.contractText, 10f)
            layout.space(14f)
            layout.section("02", "Сведения о документе и подписании")
            layout.fields(
                listOf(
                    (if (input.legacySummary) "Дата предложения" else "Дата сохранения условий") to "${DATE.format(input.sourceCreatedAt)} (МСК)",
                    "Подписание" to (input.signedAt?.let { "Синтетическое подписание: ${DATE.format(it)} (МСК)." } ?: "Факт подписания не зафиксирован."),
                ),
            )
            layout.space(10f)
            input.contractTextHash?.let { layout.paragraph("Контрольная сумма сохранённого текста (SHA-256):\n$it", 8f) }
            if (input.legacySummary) {
                layout.notice("Исходный подписанный текст не сохранён. Документ содержит сводку условий, а не подписанный оригинал.")
            } else {
                layout.notice("Демонстрационная копия. Сведения о синтетическом подписании приведены отдельно от сохранённого текста условий.")
            }
        } else {
            val rows = input.calculation.schedule
            layout.section("01", "Параметры и итог расчёта")
            layout.fields(
                listOf(
                    "Сумма кредита" to "${money(input.amount)} руб.",
                    "Срок / ставка" to "${input.termMonths} мес. / ${input.calculation.rate}% годовых",
                    "Первый платёж" to "${money(rows.first().payment)} руб.",
                    "Максимальный платёж" to "${money(rows.maxOf { it.payment })} руб.",
                    "Последний платёж" to "${money(rows.last().payment)} руб.",
                    "Всего к возврату" to "${money(input.calculation.totalPayment)} руб.",
                    "Переплата" to "${money(input.calculation.overpayment)} руб.",
                ),
            )
            layout.space(10f)
            layout.notice(scheduleNote(input))
            layout.section("02", "Платежи по месяцам · суммы в рублях")
            layout.tableHeader()
            for (row in rows) layout.tableRow(listOf(row.month.toString(), money(row.payment), money(row.principal), money(row.interest), money(row.balance)))
        }
        layout.finish()
        ByteArrayOutputStream().also(document::save).toByteArray()
    }

    private fun xlsx(input: ArtifactInput): ByteArray = XSSFWorkbook().use { workbook ->
        val sheet = workbook.createSheet("График")
        sheet.setDisplayGridlines(false)
        sheet.setPrintGridlines(false)
        workbook.properties.coreProperties.apply {
            creator = "Кабинет предпринимателя"
            title = title(ArtifactKind.SCHEDULE_XLSX, input)
        }
        val regular = workbook.createFont().apply {
            fontName = "Arial"
            fontHeightInPoints = 11
        }
        val heading = workbook.createFont().apply {
            fontName = "Arial"
            fontHeightInPoints = 16
            bold = true
        }
        val bold = workbook.createFont().apply {
            fontName = "Arial"
            fontHeightInPoints = 11
            bold = true
        }
        val text = workbook.createCellStyle().apply {
            setFont(regular)
            wrapText = true
            verticalAlignment = VerticalAlignment.CENTER
        }
        val titleStyle = workbook.createCellStyle().apply {
            cloneStyleFrom(text)
            setFont(heading)
        }
        val label = workbook.createCellStyle().apply {
            cloneStyleFrom(text)
            setFont(bold)
        }
        val numeric = workbook.createCellStyle().apply {
            setFont(regular)
            alignment = HorizontalAlignment.RIGHT
            verticalAlignment = VerticalAlignment.CENTER
            dataFormat =
                workbook.createDataFormat().getFormat("#,##0.00")
        }
        val integer = workbook.createCellStyle().apply {
            cloneStyleFrom(numeric)
            dataFormat = workbook.createDataFormat().getFormat("0")
        }
        val percentage = workbook.createCellStyle().apply {
            cloneStyleFrom(numeric)
            dataFormat = workbook.createDataFormat().getFormat("0.00%")
        }
        val header = workbook.createCellStyle().apply {
            cloneStyleFrom(label)
            alignment = HorizontalAlignment.CENTER
            fillForegroundColor =
                IndexedColors.GREY_25_PERCENT.index
            fillPattern = FillPatternType.SOLID_FOREGROUND
        }
        fun words(row: Int, column: Int, value: String, style: org.apache.poi.xssf.usermodel.XSSFCellStyle = text) {
            (sheet.getRow(row) ?: sheet.createRow(row)).createCell(column).apply {
                setCellValue(value)
                cellStyle = style
            }
        }
        fun number(row: Int, column: Int, value: Number, style: org.apache.poi.xssf.usermodel.XSSFCellStyle = numeric) {
            (sheet.getRow(row) ?: sheet.createRow(row)).createCell(column).apply {
                setCellValue(value.toDouble())
                cellStyle = style
            }
        }
        fun merged(
            row: Int,
            start: Int,
            end: Int,
            value: String,
            style: org.apache.poi.xssf.usermodel.XSSFCellStyle = text,
        ) {
            words(row, start, value, style)
            sheet.addMergedRegion(CellRangeAddress(row, row, start, end))
        }
        merged(0, 0, 4, title(ArtifactKind.SCHEDULE_XLSX, input), titleStyle)
        sheet.getRow(0).heightInPoints = 32f
        merged(
            1,
            0,
            4,
            "Заявка № ${input.applicationNumber}. Предложение ${input.offerId}. ${input.contractNumber?.let {
                "Договор $it."
            } ?: "До выдачи кредита."}",
        )
        sheet.getRow(1).heightInPoints =
            32f
        val borrower = "${input.companyName}, ИНН ${input.inn}. ${input.productName}"
        merged(2, 0, 4, borrower)
        sheet.getRow(2).heightInPoints = maxOf(32f, kotlin.math.ceil(borrower.length / 95.0).toFloat() * 16f)
        val rows = input.calculation.schedule
        merged(3, 0, 1, "Сумма, руб.", label)
        number(3, 2, input.amount)
        words(3, 3, "Ставка", label)
        number(3, 4, input.calculation.rate.toDouble() / 100, percentage)
        merged(4, 0, 1, "Срок, месяцев", label)
        number(4, 2, input.termMonths, integer)
        merged(5, 0, 1, "Всего к возврату, руб.", label)
        number(5, 2, input.calculation.totalPayment)
        words(5, 3, "Переплата, руб.", label)
        number(5, 4, input.calculation.overpayment)
        merged(6, 0, 1, "Первый платёж, руб.", label)
        number(6, 2, rows.first().payment)
        words(6, 3, "Последний платёж, руб.", label)
        number(6, 4, rows.last().payment)
        merged(7, 0, 1, "Максимальный платёж, руб.", label)
        number(7, 2, rows.maxOf { it.payment })
        merged(8, 0, 4, scheduleNote(input))
        sheet.getRow(8).heightInPoints = 44f
        listOf("Месяц", "Платёж, руб.", "Основной долг, руб.", "Проценты, руб.", "Остаток, руб.").forEachIndexed { column, value ->
            words(9, column, value, header)
        }
        sheet.getRow(9).heightInPoints = 30f
        rows.forEachIndexed { index, row ->
            val r = index + 10
            number(r, 0, row.month, integer)
            listOf(row.payment, row.principal, row.interest, row.balance).forEachIndexed { column, value -> number(r, column + 1, value) }
            sheet.getRow(r).heightInPoints = 22f
        }
        listOf(12, 23, 24, 23, 24).forEachIndexed { index, width -> sheet.setColumnWidth(index, width * 256) }
        sheet.createFreezePane(0, 10)
        sheet.setAutoFilter(CellRangeAddress(9, rows.size + 9, 0, 4))
        sheet.repeatingRows = CellRangeAddress(9, 9, 0, 4)
        sheet.printSetup.apply {
            landscape = true
            fitWidth = 1
            fitHeight = 0
        }
        sheet.fitToPage = true
        sheet.footer.right = "Страница &P из &N"
        sheet.footer.left = "Демонстрационная копия"
        ByteArrayOutputStream().also(workbook::write).toByteArray()
    }

    private fun font(document: PDDocument, name: String): PDType0Font = javaClass.getResourceAsStream("/fonts/$name")!!.use { PDType0Font.load(document, it) }

    private fun scheduleNote(input: ArtifactInput): String =
        (if (input.phase == ArtifactPhase.PREVIEW) "Предварительный расчёт по предложению. " else "По сохранённым условиям выданного кредита. ") +
            (
                if (input.calculation.revolving) {
                    "Полное использование лимита; платежи меняются, остаток погашается в конце. "
                } else {
                    "Последний платёж закрывает долг с учётом округления. "
                }
                ) +
            "Указаны номера месяцев, календарные даты платежей не определены."

    private fun money(value: Number): String = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU")).apply {
        minimumFractionDigits = 2
        maximumFractionDigits =
            2
    }.format(value)

    private class PdfLayout(
        val document: PDDocument,
        val regular: PDType0Font,
        val bold: PDType0Font,
        val reference: String,
        val heading: String,
    ) {
        private var stream: PDPageContentStream? = null
        private var y = 0f
        private val widths = listOf(45f, 115f, 123f, 108f, 112f)
        private val ink = Color(32, 37, 43)
        private val muted = Color(86, 97, 108)
        private val border = Color(209, 215, 222)
        private val accent = Color(168, 42, 48)
        private val pale = Color(244, 246, 248)
        private val glyphs = mutableMapOf<Pair<Boolean, Int>, String>()
        init {
            page()
        }
        private fun page() {
            stream?.close()
            val page = PDPage(PDRectangle.A4)
            document.addPage(page)
            stream = PDPageContentStream(document, page)
            y = page.mediaBox.height - 46f
            line("КАБИНЕТ ПРЕДПРИНИМАТЕЛЯ", 9f, true)
            right("ДЕМОНСТРАЦИОННАЯ КОПИЯ", 7f, muted)
            y -= 15f
            line("ФИНАНСИРОВАНИЕ БИЗНЕСА", 7f, color = muted)
            y -= 12f
            rule(y, accent, 1.4f)
            y -= 20f
            if (document.numberOfPages > 1) {
                line("$heading · $reference", 9f, true)
                y -= 23f
            }
        }
        private fun line(value: String, size: Float, strong: Boolean = false, x: Float = 46f, color: Color = ink) {
            stream!!.setNonStrokingColor(color)
            stream!!.beginText()
            stream!!.setFont(if (strong) bold else regular, size)
            stream!!.newLineAtOffset(x, y)
            stream!!.showText(printable(value, strong))
            stream!!.endText()
        }
        private fun right(value: String, size: Float, color: Color = ink) {
            line(value, size, x = 549f - regular.getStringWidth(value) / 1000 * size, color = color)
        }
        private fun rule(at: Float, color: Color = border, weight: Float = 0.5f) {
            stream!!.setStrokingColor(color)
            stream!!.setLineWidth(weight)
            stream!!.moveTo(46f, at)
            stream!!.lineTo(549f, at)
            stream!!.stroke()
        }
        private fun fill(bottom: Float, height: Float, color: Color) {
            stream!!.setNonStrokingColor(color)
            stream!!.addRect(46f, bottom, 503f, height)
            stream!!.fill()
        }
        private fun ensure(height: Float) {
            if (y - height < 70f) page()
        }
        private fun wrap(value: String, size: Float, strong: Boolean, width: Float): List<String> {
            val font = if (strong) bold else regular
            return printable(value, strong).split('\n').flatMap { source ->
                val result = mutableListOf<String>()
                var buffer = ""
                // Never measure half a surrogate pair, even when a glyph is covered by the font.
                for (codePoint in source.codePoints().toArray()) {
                    val character = String(Character.toChars(codePoint))
                    if (font.getStringWidth(buffer + character) / 1000 * size > width && buffer.isNotEmpty()) {
                        val split = buffer.indexOfLast { it.isWhitespace() }.takeIf { it > 0 } ?: buffer.length
                        result += buffer.substring(0, split)
                        buffer = buffer.substring(split).trimStart()
                    }
                    buffer += character
                }
                result + buffer
            }
        }
        private fun printable(value: String, strong: Boolean): String = buildString {
            value.codePoints().forEach { codePoint ->
                if (codePoint == 10) {
                    append('\n')
                } else {
                    append(
                        glyphs.getOrPut(strong to codePoint) {
                            val character = String(Character.toChars(codePoint))
                            try {
                                (if (strong) bold else regular).getStringWidth(character)
                                character
                            } catch (_: IllegalArgumentException) {
                                "[U+${codePoint.toString(16).uppercase(Locale.ROOT).padStart(4, '0')}]"
                            }
                        },
                    )
                }
            }
        }
        fun needsUnicodeNotation(values: List<String>): Boolean = values.any { value ->
            printable(value, false) != value || printable(value, true) != value
        }
        fun space(height: Float) {
            y -= height
        }
        fun section(number: String, value: String) {
            ensure(60f)
            line(number, 10f, true, color = accent)
            line(value, 10f, true, x = 71f)
            y -= 17f
        }
        fun fields(values: List<Pair<String, String>>) {
            values.forEach { (label, value) ->
                val labels = wrap(label, 8f, false, 132f)
                val lines = wrap(value, 9f, true, 339f)
                val height = maxOf(labels.size, lines.size) * 14f + 8f
                ensure(height)
                val top = y + 8f
                fill(top - height, height, pale)
                labels.forEachIndexed { index, text ->
                    line(text, 8f, x = 54f, color = muted)
                    y -= if (index == labels.lastIndex) 0f else 14f
                }
                y = top - 8f
                lines.forEach { text ->
                    line(text, 9f, true, 202f)
                    y -= 14f
                }
                y = top - height
                rule(y)
                y -= 8f
            }
        }
        fun notice(value: String) {
            val lines = wrap(value, 8f, false, 479f)
            val height = lines.size * 12f + 20f
            ensure(height)
            val top = y + 8f
            fill(top - height, height, pale)
            lines.forEach {
                line(it, 8f, x = 58f, color = muted)
                y -= 12f
            }
            y = top - height - 14f
        }
        fun paragraph(value: String, size: Float, strong: Boolean = false) {
            for (text in wrap(value, size, strong, 503f)) {
                ensure(size * 1.5f)
                line(text, size, strong)
                y -= size * 1.5f
            }
        }
        fun tableHeader() {
            ensure(66f)
            tableRow(listOf("Месяц", "Платёж", "Основной долг", "Проценты", "Остаток"), true)
        }
        fun tableRow(values: List<String>, header: Boolean = false) {
            if (y - 17f < 70f) {
                page()
                if (!header) tableHeader()
            }
            if (header) fill(y - 9f, 25f, pale)
            var x = 46f
            values.forEachIndexed { index, value ->
                val width = (if (header) bold else regular).getStringWidth(value) / 1000 * 9f
                line(value, 9f, header, if (index == 0 || header) x + 6f else x + widths[index] - width - 6f)
                x += widths[index]
            }
            rule(y - if (header) 9f else 7f, if (header) muted else border)
            y -= if (header) 27f else 17f
        }
        fun finish() {
            stream?.close()
            stream = null
            document.pages.forEachIndexed { index, page ->
                PDPageContentStream(document, page, PDPageContentStream.AppendMode.APPEND, true, true).use { footer ->
                    footer.setStrokingColor(border)
                    footer.setLineWidth(0.5f)
                    footer.moveTo(46f, 49f)
                    footer.lineTo(549f, 49f)
                    footer.stroke()
                    footer.setNonStrokingColor(muted)
                    footer.beginText()
                    footer.setFont(regular, 7f)
                    footer.newLineAtOffset(46f, 33f)
                    footer.showText("$reference · Демонстрационная копия")
                    footer.endText()
                    val pageNumber = "Страница ${index + 1} из ${document.numberOfPages}"
                    footer.beginText()
                    footer.setFont(regular, 7f)
                    footer.newLineAtOffset(549f - regular.getStringWidth(pageNumber) / 1000 * 7f, 33f)
                    footer.showText(pageNumber)
                    footer.endText()
                }
            }
        }
    }

    companion object {
        const val TEMPLATE_VERSION = 2
        private val DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(java.time.ZoneId.of("Europe/Moscow"))
    }
}
