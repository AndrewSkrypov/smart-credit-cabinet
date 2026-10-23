package ru.creditcabinet.chat.infrastructure

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.chat.domain.ChatLimits
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.limits.LimitWindow
import ru.creditcabinet.common.limits.SharedLimits
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Valkey counts across replicas; the database implementation remains for isolated tests. */
@Component
class ChatThrottle(private val jdbc: JdbcTemplate, private val clock: Clock, private val shared: SharedLimits) : ChatLimits {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun acquire(actorId: UUID) {
        if (shared.enabled) {
            shared.acquire(
                "Слишком много сообщений подряд. Можно отправить 30 сообщений за минуту.",
                LimitWindow("chat", actorId.toString(), 30, Duration.ofMinutes(1)),
            )
            return
        }
        val start = Timestamp.from(Instant.ofEpochSecond(clock.instant().epochSecond / 60 * 60))
        val accepted = jdbc.queryForList(
            """
            insert into application_chat_rate_buckets(actor_id,window_start,hits) values (?,?,1)
            on conflict (actor_id) do update set window_start=greatest(application_chat_rate_buckets.window_start,excluded.window_start),
                hits=case when excluded.window_start>application_chat_rate_buckets.window_start then 1 else application_chat_rate_buckets.hits+1 end
            where excluded.window_start>application_chat_rate_buckets.window_start or application_chat_rate_buckets.hits<30 returning hits
            """.trimIndent(),
            actorId,
            start,
        ).isNotEmpty()
        if (!accepted) throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "Слишком много сообщений подряд. Можно отправить 30 сообщений за минуту.")
    }
}
