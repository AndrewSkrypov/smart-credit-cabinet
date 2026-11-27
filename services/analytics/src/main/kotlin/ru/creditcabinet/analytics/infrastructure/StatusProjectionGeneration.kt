package ru.creditcabinet.analytics.infrastructure

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class StatusProjectionGeneration(private val jdbc: JdbcTemplate) {
    fun isInitialized(topic: String): Boolean =
        jdbc.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM application_status_generation WHERE singleton AND generation = ?)",
            Boolean::class.java,
            topic,
        ) == true
}
