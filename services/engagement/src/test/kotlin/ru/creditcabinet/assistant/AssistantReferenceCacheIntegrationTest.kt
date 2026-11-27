package ru.creditcabinet.assistant

import ru.creditcabinet.common.limits.ValkeyProperties
import ru.creditcabinet.common.limits.ValkeyState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AssistantReferenceCacheIntegrationTest {
    private val properties = ValkeyProperties(
        enabled = true,
        port = System.getenv("VALKEY_TEST_PORT")?.toInt() ?: 16379,
        password = "dev-valkey-integration-password",
    )
    private val namespace = "assistant-reference-integration-${UUID.randomUUID()}"

    @Test
    fun `the manual review cache shares current revisions and rejects late publication across replicas`() {
        ValkeyState(properties, namespace).use { writer ->
            ValkeyState(properties, namespace).use { reader ->
                val first = ManualReviewSummaryCache(writer)
                val second = ManualReviewSummaryCache(reader)
                val application = UUID.randomUUID()
                val firstEvent = UUID.randomUUID()
                val nextEvent = UUID.randomUUID()
                val firstRevision = "a".repeat(64)
                val nextRevision = "b".repeat(64)

                assertTrue(first.publish(application, null, firstEvent, firstRevision, "Первый вывод"))
                assertEquals(firstEvent, second.eventFor(application))
                assertEquals("Первый вывод", second.text(application, firstRevision))
                assertTrue(second.publish(application, firstEvent, nextEvent, nextRevision, "Текущий вывод"))
                assertFalse(first.publish(application, firstEvent, firstEvent, firstRevision, "Запоздавший вывод"))
                assertEquals(nextEvent, first.eventFor(application))
                assertEquals("Текущий вывод", first.text(application, nextRevision))
                assertNull(second.text(UUID.randomUUID(), nextRevision))
            }
        }
    }

    @Test
    fun `public reference is shared across replicas with a TTL and separated by model and source`() {
        ValkeyState(properties, namespace).use { writer ->
            ValkeyState(properties, namespace).use { reader ->
                val first = AssistantReferenceCache(writer)
                val second = AssistantReferenceCache(reader)
                val question = "Что такое овердрафт?"
                val articles = Knowledge.relevant(question)
                val model = "qwen2.5:1.5b"
                val key = assertNotNull(first.key(question, articles, model))
                val answer = articles.first().text
                first.save(key, answer)
                assertEquals(answer, second.read(key))
                val ttl = reader.eval("return redis.call('TTL', KEYS[1])", listOf(key), emptyList()).toString().toLong()
                assertTrue(ttl in 1..1800)
                assertNotEquals(key, second.key(question, articles, "qwen2.5:3b"))
                val edited = articles.first().copy(text = articles.first().text + ".")
                assertNotEquals(key, second.key(question, listOf(edited) + articles.drop(1), model))
                assertNull(second.key("Мне уже одобрили овердрафт по моей заявке?", articles, model))
            }
        }
    }
}
