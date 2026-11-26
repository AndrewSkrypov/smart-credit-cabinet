package ru.creditcabinet.analytics.infrastructure

import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class StatusProjectionReadinessTest {
    private val first = TopicPartition("source", 0)
    private val second = TopicPartition("source", 1)
    private val deadLetter = TopicPartition("dlq", 0)
    private val source = listOf(first, second)
    private val dlq = listOf(deadLetter)
    private val starts = mapOf(first to 0L, second to 0L)
    private val ends = mapOf(first to 109L, second to 10L, deadLetter to 0L)

    @Test
    fun `all partitions must be within the shared offset budget`() {
        assertEquals(Readiness.READY, evaluateProgress(source, dlq, starts, ends, mapOf(first to 109L, second to 10L), 0))
        assertEquals(Readiness.LAG, evaluateProgress(source, dlq, starts, ends, mapOf(first to 108L, second to 9L), 1))
        assertEquals(Readiness.READY, evaluateProgress(source, dlq, starts, ends, mapOf(first to 108L, second to 9L), 2))
    }

    @Test
    fun `unknown offsets and a truncated unconsumed source fail closed`() {
        assertEquals(Readiness.OFFSET_UNAVAILABLE, evaluateProgress(source, dlq, starts, ends, mapOf(first to 109L), 10))
        assertEquals(
            Readiness.OFFSET_UNAVAILABLE,
            evaluateProgress(source, dlq, starts + (second to 9L), ends, mapOf(first to 109L, second to 7L), 10),
        )
        assertEquals(
            Readiness.OFFSET_UNAVAILABLE,
            evaluateProgress(source, dlq, starts, ends, mapOf(first to 110L, second to 10L), 0),
        )
        assertEquals(
            Readiness.OFFSET_UNAVAILABLE,
            evaluateProgress(source, dlq, starts, ends - deadLetter, mapOf(first to 109L, second to 10L), 0),
        )
        val emptyGeneration = source.associateWith { 0L } + (deadLetter to 0L)
        assertEquals(Readiness.READY, evaluateProgress(source, dlq, starts, emptyGeneration, emptyMap(), 0))
    }

    @Test
    fun `a quarantined event prevents readiness even with caught up offsets`() {
        assertEquals(Readiness.DLQ, evaluateProgress(source, dlq, starts, ends + (deadLetter to 1L), mapOf(first to 109L, second to 10L), 10))
    }
}
