package ru.creditcabinet.assessmentservice

import org.springframework.http.MediaType
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestClient
import ru.creditcabinet.assessmentservice.application.AssessmentSourceUnavailable
import ru.creditcabinet.assessmentservice.infrastructure.SimulatorAssessmentSources
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertFailsWith

class SimulatorAssessmentSourcesTest {
    @Test
    fun `registry rejects a response with an INN different from the request`() {
        val builder = RestClient.builder().baseUrl("http://127.0.0.1")
        val server = MockRestServiceServer.bindTo(builder).build()
        server.expect(requestTo("http://127.0.0.1/ext/registry/v1/companies/000000000001")).andRespond(
            withSuccess(
                """
                {
                  "inn": "000000000002", "ogrn": "300000000000001", "fullName": "Synthetic", "shortName": "Synthetic",
                  "status": "ACTIVE", "statusTitle": "Active", "registrationDate": "2020-01-01",
                  "okvedMain": {"code": "56.10", "name": "Cafe"}, "director": {"fullName": "Synthetic"},
                  "address": "Synthetic", "unreliableAddress": false, "unreliableDirector": false
                }
                """.trimIndent(),
                MediaType.APPLICATION_JSON,
            ),
        )

        assertFailsWith<AssessmentSourceUnavailable> {
            SimulatorAssessmentSources(builder.build(), Clock.systemUTC()).registry("000000000001")
        }
        server.verify()
    }
}
