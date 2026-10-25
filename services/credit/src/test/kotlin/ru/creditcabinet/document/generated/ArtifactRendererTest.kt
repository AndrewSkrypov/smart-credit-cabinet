package ru.creditcabinet.document.generated

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import ru.creditcabinet.offer.ContractTexts
import ru.creditcabinet.product.domain.LoanMath
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ArtifactRendererTest {
    private val renderer = ArtifactRenderer()
    private val contract = "Кредитный договор по заявке № 1002\nЗаёмщик: ООО «Пример», ИНН 000100000004\nСумма: 1 000 000 ₽. Ставка: 20% годовых."
    private fun input(months: Int = 12, revolving: Boolean = false) = ArtifactInput(
        UUID.fromString("00000000-0000-0000-0000-000000001002"), 1002,
        UUID.fromString("00000000-0000-0000-0000-000000000001"), "ООО «Пример»", "000100000004", "Кредит для бизнеса",
        1_000_000, months, LoanMath.calculate(1_000_000, BigDecimal("20.00"), months, revolving), ArtifactPhase.FINAL,
        contract, ContractTexts.sha256(contract), false, Instant.parse("2026-10-24T12:00:00Z"), "КД-2026-1002", Instant.parse("2026-10-24T11:00:00Z"),
    )

    @Test
    fun `contract PDF contains the exact visible text its hash embedded Cyrillic and separate signing fact`() {
        val source = input()
        val bytes = renderer.render(ArtifactKind.CONTRACT_PDF, source)
        Loader.loadPDF(bytes).use { pdf ->
            val text = PDFTextStripper().getText(pdf)
            fun normalize(value: String) = value.replace(Regex("[\\s\u00a0\u202f]+"), " ").trim()
            assertTrue(normalize(text).contains(normalize(contract)), text)
            assertEquals(source.contractTextHash, pdf.documentInformation.getCustomMetadataValue("CabinetContractTextSHA256"))
            assertTrue(text.contains("Синтетическое подписание"))
            assertTrue(pdf.pages.all { page -> page.resources.fontNames.all { page.resources.getFont(it).isEmbedded } })
        }
        sample("contract-demo.pdf", bytes)
    }

    @Test
    fun `long schedule repeats readable table headers and closes exactly on the server final payment`() {
        val source = input(120, true)
        val bytes = renderer.render(ArtifactKind.SCHEDULE_PDF, source)
        Loader.loadPDF(bytes).use { pdf ->
            assertTrue(pdf.numberOfPages >= 3)
            for (i in 1..pdf.numberOfPages) {
                val text = PDFTextStripper().apply {
                    startPage = i
                    endPage = i
                }.getText(pdf)
                assertTrue(text.contains("Основной долг"), "Missing repeated header on page $i")
                assertTrue(text.contains("Страница $i из ${pdf.numberOfPages}"))
            }
            assertTrue(PDFTextStripper().getText(pdf).contains("120"))
        }
        sample("schedule-120-months.pdf", bytes)
    }

    @Test
    fun `bank form wraps long borrower names and exact contract paragraphs within printable margins`() {
        val text = contract + "\n" + (1..70).joinToString("\n") { "Условие $it. Сохранённый текст предложения с длинным пояснением условий кредитования." }
        val source = input().copy(
            companyName = "ООО «" + "Длинное наименование ".repeat(15) + "»",
            contractText = text,
            contractTextHash = ContractTexts.sha256(text),
        )
        Loader.loadPDF(renderer.render(ArtifactKind.CONTRACT_PDF, source)).use { pdf ->
            assertTrue(pdf.numberOfPages >= 2)
            val stripper = object : PDFTextStripper() {
                override fun writeString(value: String, positions: MutableList<TextPosition>) {
                    positions.forEach { position ->
                        assertTrue(position.xDirAdj >= 45f && position.xDirAdj + position.widthDirAdj <= 550f, "Text crossed horizontal margins: $value")
                        assertTrue(position.yDirAdj <= 810f, "Text crossed the footer: $value")
                    }
                    super.writeString(value, positions)
                }
            }
            val visible = stripper.getText(pdf).replace(Regex("[\\s\u00a0\u202f]+"), " ")
            assertTrue(visible.contains("Условие 70."))
            assertTrue(visible.contains(source.contractTextHash!!))
        }
    }

    @Test
    fun `legacy PDF names the reconstructed summary honestly and never invents a text fingerprint`() {
        val source = input().copy(
            legacySummary = true,
            contractText = "Сводка сохранённых условий. Исходный подписанный текст не сохранён.",
            contractTextHash = null,
        )
        Loader.loadPDF(renderer.render(ArtifactKind.CONTRACT_PDF, source)).use { pdf ->
            val visible = PDFTextStripper().getText(pdf)
            assertTrue(visible.contains("СВОДКА СОХРАНЁННЫХ УСЛОВИЙ"))
            assertTrue(visible.contains("Сводка условий кредита"))
            assertTrue(!visible.contains("Сохранённый текст условий"))
            assertEquals(null, pdf.documentInformation.getCustomMetadataValue("CabinetContractTextSHA256"))
        }
    }

    @Test
    fun `XLSX stores all frozen schedule values as numbers with a zero last balance`() {
        val source = input(120, true).copy(companyName = "ООО «" + "Длинное наименование компании ".repeat(10) + "»")
        val bytes = renderer.render(ArtifactKind.SCHEDULE_XLSX, source)
        XSSFWorkbook(bytes.inputStream()).use { workbook ->
            assertEquals(1, workbook.numberOfSheets)
            val sheet = workbook.getSheetAt(0)
            assertEquals(129, sheet.lastRowNum)
            assertEquals(10, sheet.paneInformation.horizontalSplitPosition.toInt())
            source.calculation.schedule.forEachIndexed { index, expected ->
                val row = sheet.getRow(index + 10)
                assertEquals(expected.month.toDouble(), row.getCell(0).numericCellValue)
                listOf(expected.payment, expected.principal, expected.interest, expected.balance).forEachIndexed { column, value ->
                    val cell = row.getCell(column + 1)
                    assertEquals(CellType.NUMERIC, cell.cellType)
                    assertEquals(value, BigDecimal.valueOf(cell.numericCellValue).setScale(2))
                }
            }
            assertEquals(0.0, sheet.getRow(129).getCell(4).numericCellValue)
            assertEquals(CellType.STRING, sheet.getRow(2).getCell(0).cellType)
            assertTrue(sheet.getRow(2).heightInPoints >= 64f)
        }
        sample("schedule-120-months.xlsx", bytes)
    }

    private fun sample(name: String, bytes: ByteArray) {
        val directory = Path.of("build", "artifact-samples")
        Files.createDirectories(directory)
        Files.write(directory.resolve(name), bytes)
    }
}
