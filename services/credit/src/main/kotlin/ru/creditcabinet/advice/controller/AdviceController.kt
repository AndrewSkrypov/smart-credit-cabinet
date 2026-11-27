package ru.creditcabinet.advice.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import io.swagger.v3.oas.annotations.Hidden
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.advice.application.AdviceService
import ru.creditcabinet.advice.application.AdviceView
import java.security.MessageDigest
import java.util.UUID

@Tag(name = "Решения сотрудников банка")
@RestController
@RequestMapping("/api/v1/underwriting/applications")
class AdviceController(private val service: AdviceService) {
    @Operation(summary = "Рекомендации экспертной системы: что проверить, что запросить, какие условия проходят — с цепочкой «почему»")
    @GetMapping("/{id}/advice")
    fun advice(@PathVariable id: UUID): AdviceView = service.advice(id)
}

/** For engagement background summary warmup. Not routed by the gateway (/internal is closed with 404). */
@Hidden
@RestController
@RequestMapping("/internal/v1/underwriting/applications")
class InternalAdviceController(private val service: AdviceService, private val mapper: ObjectMapper) {
    @GetMapping("/{id}/advice")
    fun manualReviewAdvice(@PathVariable id: UUID, @RequestParam eventId: UUID): ObjectNode {
        val advice = service.manualReviewAdvice(id, eventId)
        val node = mapper.valueToTree<ObjectNode>(advice)
        val revision = MessageDigest.getInstance("SHA-256")
            .digest("$eventId:${mapper.writeValueAsString(advice)}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return node.put("adviceRevision", revision)
    }
}
