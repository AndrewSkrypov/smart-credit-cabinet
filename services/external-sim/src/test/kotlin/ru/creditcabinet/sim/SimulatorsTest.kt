package ru.creditcabinet.sim

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import ru.creditcabinet.common.model.Inn
import ru.creditcabinet.sim.control.Faults
import java.util.UUID
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@SpringBootTest(properties = ["cabinet.sim.latency-ms=0", "cabinet.sim.slow-delay-ms=0", "cabinet.sim.api-key=test-api-key-123"])
@AutoConfigureMockMvc
class SimulatorsTest {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var mapper: ObjectMapper

    @Autowired
    private lateinit var faults: Faults

    @AfterTest
    fun reset() = faults.reset()

    private fun call(url: String, consent: Boolean = false) =
        mockMvc.get(url) {
            header("X-Api-Key", "test-api-key-123")
            if (consent) header("X-Consent-Id", "consent-1")
        }

    private fun ResultActionsDsl.json(): JsonNode = mapper.readTree(andReturn().response.getContentAsString(Charsets.UTF_8))

    private fun status(actions: ResultActionsDsl) = actions.andReturn().response.status

    @Test
    fun `a wrong key is refused before anything is read`() {
        mockMvc.get("/ext/registry/v1/companies/0001001014") { header("X-Api-Key", "guess") }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `the registry knows scenario companies, generates any other synthetic number and nothing else`() {
        val liquidating = call("/ext/registry/v1/companies/0001001039").json()
        assertEquals("LIQUIDATING", liquidating["status"].asText())
        assertEquals("ЕГРЮЛ", liquidating["registry"].asText())

        val generated = call("/ext/registry/v1/companies/000100099970").json()
        assertEquals("ENTREPRENEUR", generated["legalForm"].asText())
        assertEquals(generated, call("/ext/registry/v1/companies/000100099970").json(), "the same number gives the same company")
        val ogrnip = generated["ogrn"].asText()
        assertEquals(15, ogrnip.length)
        assertEquals(ogrnip.take(14).toLong() % 13 % 10, ogrnip.last().digitToInt().toLong())

        assertEquals(404, status(call("/ext/registry/v1/companies/7707083893")), "a real company is never described")
    }

    @Test
    fun `the bureau answers only with a consent and the agro company always fails`() {
        assertEquals(400, status(call("/ext/bureau/v1/reports/0001001014")))
        val report = call("/ext/bureau/v1/reports/0001001014", consent = true).json()
        assertEquals(950_000, report["monthlyPaymentsTotal"].asLong())
        assertEquals(120, call("/ext/bureau/v1/reports/000100020145", consent = true).json()["currentOverdueDaysMax"].asInt())
        assertEquals(503, status(call("/ext/bureau/v1/reports/0001001053", consent = true)))
    }

    @Test
    fun `the tax service has no reports for a business registered this year`() {
        val young = call("/ext/fns/v1/companies/000100020392/summary").json()
        assertEquals(false, young["hasReports"].asBoolean())
        assertTrue(young["revenue"].isNull)
        assertEquals(150_000, call("/ext/fns/v1/companies/000100020219/summary").json()["taxDebt"].asLong())
    }

    @Test
    fun `bank turnovers have the coefficient of variation of the dataset`() {
        val turnovers = call("/ext/bank/v1/clients/0001001021").json()["turnovers"].map { it["inflow"].asDouble() }
        assertEquals(12, turnovers.size)
        val mean = turnovers.average()
        val cv = sqrt(turnovers.sumOf { (it - mean) * (it - mean) } / turnovers.size) / mean
        assertTrue(abs(cv - 0.35) < 0.01, "cv = $cv")
        assertEquals(false, call("/ext/bank/v1/clients/0001001046").json()["client"].asBoolean())
    }

    @Test
    fun `a source switched off on the panel fails until it is switched back`() {
        mockMvc.put("/sim/control/sources/FNS") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"mode":"DOWN"}"""
        }.andExpect { status { isOk() } }
        assertEquals(503, status(call("/ext/fns/v1/companies/0001001014/summary")))
        mockMvc.post("/sim/control/reset").andExpect { status { isOk() } }
        assertEquals(200, status(call("/ext/fns/v1/companies/0001001014/summary")))
    }

    @Test
    fun `signing takes the issued code, refuses a wrong one and repeats the same signature`() {
        val session = mockMvc.post("/ext/signing/v1/sessions") {
            header("X-Api-Key", "test-api-key-123")
            contentType = MediaType.APPLICATION_JSON
            content = """{"documentId":"contract-1","documentTitle":"Кредитный договор","documentText":"Текст","signerName":"Тест"}"""
        }.json()
        val id = session["sessionId"].asText()
        val code = session["code"].asText()
        fun confirm(value: String) =
            mockMvc.post("/ext/signing/v1/sessions/$id/confirm") {
                header("X-Api-Key", "test-api-key-123")
                contentType = MediaType.APPLICATION_JSON
                content = """{"code":"$value"}"""
            }
        val wrong = if (code == "000000") "111111" else "000000"
        assertEquals(400, status(confirm(wrong)))
        val signed = confirm(code).json()
        assertEquals(signed["signatureId"], confirm(code).json()["signatureId"])
    }

    @Test
    fun `one application gives one loan however often disbursement is repeated`() {
        val applicationId = UUID.randomUUID()
        fun disburse() =
            mockMvc.post("/ext/core/v1/loans") {
                header("X-Api-Key", "test-api-key-123")
                contentType = MediaType.APPLICATION_JSON
                content =
                    """{"applicationId":"$applicationId","companyInn":"000100000004","amount":1000000,"termMonths":12,"annualRate":20,"revolving":false,"signatureId":"${UUID.randomUUID()}"}"""
            }.json()
        val first = disburse()
        assertEquals(first["contractNumber"], disburse()["contractNumber"])
        assertNotEquals("", first["loanAccount"].asText())
    }

    @Test
    fun `every scenario company has valid numbers`() {
        val state = mapper.readTree(mockMvc.get("/sim/control").andReturn().response.getContentAsString(Charsets.UTF_8))
        val inns = state["companies"].map { it["inn"].asText() }
        assertEquals(10, inns.size)
        assertTrue(inns.all { Inn.isValid(it) && it.startsWith("00") })
    }
}
