package ru.creditcabinet.application

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.common.security.JwtVerifier
import ru.creditcabinet.common.security.TokenClaims
import java.util.Date
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(properties = ["cabinet.pipeline.enabled=false", "spring.jpa.properties.hibernate.generate_statistics=true"])
@AutoConfigureMockMvc
@Transactional
class ApplicationPagesIntegrationTest {
    @Autowired private lateinit var mvc: MockMvc

    @Autowired private lateinit var jdbc: JdbcTemplate

    @Autowired private lateinit var verifier: JwtVerifier

    @Autowired private lateinit var mapper: ObjectMapper

    @Autowired private lateinit var factory: EntityManagerFactory

    private fun token(role: String, company: UUID? = null, canSign: Boolean = true): String =
        Jwts.builder().subject(UUID.randomUUID().toString()).claim(TokenClaims.ROLE, role)
            .claim(TokenClaims.FULL_NAME, "Проверка страниц").claim(TokenClaims.CAN_SIGN, canSign)
            .apply { company?.let { claim(TokenClaims.COMPANY_ID, it.toString()) } }
            .expiration(Date(System.currentTimeMillis() + 3_600_000)).signWith(verifier.signingKey).compact()

    private fun page(token: String, query: String, status: Int = 200): JsonNode {
        val response = mvc.get("/api/v1/applications/page?$query") { header("Authorization", "Bearer $token") }.andReturn().response
        assertEquals(status, response.status, response.contentAsString)
        return mapper.readTree(response.getContentAsString(Charsets.UTF_8))
    }

    @Test
    fun `SQL bounds large lists and enforces rights search counts ties and invalid parameters`() {
        val company = UUID.randomUUID()
        val foreign = UUID.randomUUID()
        val prefix = "Followup-$company"
        val user = UUID.randomUUID()
        fun seed(owner: UUID, product: String, status: String, count: Int, fixedNumbers: Boolean = false) {
            jdbc.update(
                """
                insert into applications (id, number, company_id, company_inn, company_name, legal_form, created_by,
                    product_code, purpose, amount, term_months, status, created_at, updated_at, status_changed_at,
                    product_version, product_catalog_snapshot)
                select case when ? then ('a0000000-0000-4000-8000-' || lpad(s::text, 12, '0'))::uuid else md5(? || s::text)::uuid end,
                    case when ? then 8000000000000 + s else nextval('application_number_seq') end,
                    ?, '000000000000', ?, 'ENTREPRENEUR', ?, ?, 'WORKING_CAPITAL',
                    100000, 12, ?, '2026-10-25T00:00:00Z', '2026-10-25T00:00:00Z', '2026-10-25T00:00:00Z',
                    1, '{"WORKING_CAPITAL":1}'::jsonb from generate_series(1, ?) s
                """.trimIndent(),
                fixedNumbers,
                UUID.randomUUID().toString(),
                fixedNumbers,
                owner,
                prefix,
                user,
                product,
                status,
                count,
            )
        }
        seed(company, "WORKING_CAPITAL", "REJECTED", 1001, fixedNumbers = true)
        seed(company, "WORKING_CAPITAL", "MANUAL_REVIEW", 1)
        seed(company, "OVERDRAFT", "DRAFT", 1)
        seed(company, "BUSINESS_CARD", "SIGNING", 1)
        seed(foreign, "WORKING_CAPITAL", "MANUAL_REVIEW", 1)
        val client = token("CLIENT", company)
        val bank = token("UNDERWRITER")
        val stats = factory.unwrap(SessionFactory::class.java).statistics
        stats.clear()
        val first = page(client, "group=done&size=20")
        assertEquals(20, first["items"].size())
        assertEquals(1001, first["totalElements"].asInt())
        assertEquals(51, first["totalPages"].asInt())
        assertEquals(3, first["countsByGroup"]["active"].asInt())
        assertEquals(2, first["actionRequiredCount"].asInt())
        assertTrue(stats.entityLoadCount in 20..25, "Only the bounded page/preview may be materialized, loaded ${stats.entityLoadCount}")
        val second = page(client, "group=done&size=20&page=1")
        assertTrue(first["items"].none { a -> second["items"].any { b -> a["id"] == b["id"] } })
        assertEquals(second["items"], page(client, "group=done&size=20&page=1")["items"])
        val last = page(client, "group=done&size=20&page=50")
        assertEquals(1, last["items"].size())
        assertEquals(last["items"], page(client, "group=done&size=20&page=999")["items"])
        val distantNumber = "8000000000001"
        assertEquals(distantNumber, last["items"][0]["number"].asText(), "The deterministic target is beyond the first page")
        val found = page(client, "group=done&q=$distantNumber")
        assertEquals(1, found["totalElements"].asInt())
        assertEquals(distantNumber, found["items"][0]["number"].asText())
        val needle = "000000010"
        val expectedNumbers = (1..1001).map { 8000000000000L + it }.filter { needle in it.toString() }.toSet()
        assertEquals(13, expectedNumbers.size)
        val substring = page(client, "group=done&size=100&q=$needle")
        assertEquals(expectedNumbers.size, substring["totalElements"].asInt())
        assertEquals(expectedNumbers, substring["items"].map { it["number"].asLong() }.toSet())
        assertEquals(2, found["actionRequiredCount"].asInt(), "Action preview is global, independent of search")
        assertEquals(0, found["countsByGroup"]["active"].asInt())
        assertEquals(0, page(token("CLIENT", foreign), "group=done")["totalElements"].asInt())
        val noSign = page(token("CLIENT", company, false), "group=action")
        assertEquals(1, noSign["totalElements"].asInt())
        assertTrue(noSign["items"].none { it["status"].asText() == "SIGNING" })
        val review = page(bank, "group=review&q=$prefix")
        assertEquals(2, review["totalElements"].asInt())
        assertTrue(review["items"].all { it["status"].asText() == "MANUAL_REVIEW" })
        assertEquals(2, review["countsByGroup"]["review"].asInt())
        assertEquals(1, review["countsByGroup"]["client"].asInt())
        assertEquals(1001, review["countsByGroup"]["done"].asInt())
        for (count in listOf(0, 1, 20, 21)) {
            val owner = UUID.randomUUID()
            if (count > 0) seed(owner, "WORKING_CAPITAL", "REJECTED", count)
            val rows = page(token("CLIENT", owner), "group=done&size=20")
            assertEquals(count, rows["totalElements"].asInt())
            assertEquals(minOf(count, 20), rows["items"].size())
            assertEquals(count > 20, rows["hasNext"].asBoolean())
        }
        assertEquals(0, page(client, "group=done&q=%25")["totalElements"].asInt())
        assertEquals(0, page(client, "group=done&q=absent-$company")["totalPages"].asInt())
        assertEquals(0, page(client, "group=done&q=absent-$company&page=50")["page"].asInt())
        for (query in listOf(
            "size=0",
            "size=101",
            "page=-1",
            "page=2147483647&size=100",
            "group=review",
            "sort=unsafe",
            "q=${"a".repeat(101)}",
        )) {
            page(client, query, 400)
        }
        page(bank, "group=active", 400)
        assertEquals(401, mvc.get("/api/v1/applications/page").andReturn().response.status)
        val plan = jdbc.queryForObject(
            "explain (analyze, format json) select id from applications where company_id = ? and status in ('REJECTED', 'DISBURSED', 'WITHDRAWN', 'CLOSED') order by created_at desc, id desc limit 20 offset 20",
            String::class.java,
            company,
        )!!
        val root = mapper.readTree(plan)[0]["Plan"]
        assertEquals("Limit", root["Node Type"].asText())
        assertEquals(20, root["Actual Rows"].asInt())
        println("Bounded application query EXPLAIN: $plan")
    }
}
