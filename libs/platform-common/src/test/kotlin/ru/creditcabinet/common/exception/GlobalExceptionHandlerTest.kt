package ru.creditcabinet.common.exception

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Malformed input is the caller's mistake: 400 with a Russian explanation, never a 500. */
class GlobalExceptionHandlerTest {
    enum class Color { RED, GREEN }

    data class Body(val color: Color, val count: Int, val title: String, val day: LocalDate? = null)

    @RestController
    class Probe {
        @GetMapping("/things/{id}")
        fun thing(@PathVariable id: UUID) = mapOf("id" to id)

        @GetMapping("/things")
        fun things(@RequestParam color: Color?, @RequestParam(defaultValue = "0") page: Int, @RequestParam(required = false) from: LocalDate?) =
            mapOf("color" to color, "page" to page)

        @GetMapping("/required")
        fun required(@RequestParam query: String) = query

        @GetMapping("/header")
        fun header(@RequestHeader("X-Case") case: String) = case

        @PostMapping("/things")
        fun create(@RequestBody body: Body) = body

        @GetMapping("/boom")
        fun boom(): String = error("internal detail that must not leak")
    }

    private val mapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule()).disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    private val mockMvc: MockMvc = MockMvcBuilders.standaloneSetup(Probe())
        .setControllerAdvice(GlobalExceptionHandler())
        .setMessageConverters(MappingJackson2HttpMessageConverter(mapper))
        .build()

    private fun message(body: String): String = mapper.readTree(body)["message"].asText()

    private fun badRequest(result: org.springframework.test.web.servlet.ResultActionsDsl): String {
        val response = result.andReturn().response
        assertEquals(400, response.status, response.contentAsString)
        return message(response.getContentAsString(Charsets.UTF_8))
    }

    @Test
    fun `an invalid UUID in the path is 400 naming the parameter`() {
        val text = badRequest(mockMvc.get("/things/not-a-uuid"))
        assertTrue(text.startsWith("Параметр «id»: нужен идентификатор"), text)
        assertTrue(text.contains("«not-a-uuid»"), text)
        mockMvc.get("/things/${UUID.randomUUID()}").andExpect { status { isOk() } }
    }

    @Test
    fun `an unknown enum, a non-number and a bad date in query parameters are 400`() {
        assertEquals("Параметр «color»: допустимые значения — RED, GREEN, а передано «BLUE»", badRequest(mockMvc.get("/things?color=BLUE")))
        assertTrue(badRequest(mockMvc.get("/things?page=first")).startsWith("Параметр «page»: нужно целое число"))
        assertTrue(badRequest(mockMvc.get("/things?from=30.09.2026")).startsWith("Параметр «from»: нужна дата в формате ГГГГ-ММ-ДД"))
    }

    @Test
    fun `a very long wrong value is not echoed back whole`() {
        val text = badRequest(mockMvc.get("/things/${"x".repeat(500)}"))
        assertTrue(text.length < 200, text)
    }

    @Test
    fun `missing parameters and headers are named`() {
        assertEquals("Не указан параметр «query»", badRequest(mockMvc.get("/required")))
        assertEquals("Не указан заголовок «X-Case»", badRequest(mockMvc.get("/header")))
    }

    private fun post(json: String) =
        mockMvc.post("/things") {
            contentType = MediaType.APPLICATION_JSON
            content = json
        }

    @Test
    fun `broken JSON, wrong field types and missing fields are 400 naming the field`() {
        assertEquals("Тело запроса — не корректный JSON", badRequest(post("""{"color": "RED", """)))
        assertEquals("Поле «color»: допустимые значения — RED, GREEN", badRequest(post("""{"color":"BLUE","count":1,"title":"t"}""")))
        assertEquals("Поле «count»: нужно целое число", badRequest(post("""{"color":"RED","count":"много","title":"t"}""")))
        assertEquals("Поле «title» обязательно", badRequest(post("""{"color":"RED","count":1}""")))
        assertEquals("Поле «day»: нужна дата в формате ГГГГ-ММ-ДД", badRequest(post("""{"color":"RED","count":1,"title":"t","day":"вчера"}""")))
        assertEquals("Нет тела запроса", badRequest(post("")))
        post("""{"color":"RED","count":1,"title":"t"}""").andExpect { status { isOk() } }
    }

    @Test
    fun `an unexpected failure stays a 500 without internals`() {
        val response = mockMvc.get("/boom").andReturn().response
        assertEquals(500, response.status)
        val text = message(response.getContentAsString(Charsets.UTF_8))
        assertFalse(text.contains("internal detail"), text)
    }
}
