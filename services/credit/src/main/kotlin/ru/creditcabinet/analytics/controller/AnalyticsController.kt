package ru.creditcabinet.analytics.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import ru.creditcabinet.analytics.application.Analytics
import ru.creditcabinet.analytics.application.AnalyticsQuery
import java.time.Instant

@Tag(name = "Аналитика конвейера")
@RestController
@RequestMapping("/api/v1/analytics")
class AnalyticsController(private val query: AnalyticsQuery) {
    @Operation(summary = "Воронка, время на этапах и исходы решений за период (по умолчанию 30 дней)")
    @GetMapping("/funnel")
    fun funnel(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) from: Instant?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) to: Instant?,
    ): Analytics = query.funnel(from, to)
}
