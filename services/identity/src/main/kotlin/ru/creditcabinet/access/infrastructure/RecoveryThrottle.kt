package ru.creditcabinet.access.infrastructure

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import ru.creditcabinet.access.domain.AccessGrant
import ru.creditcabinet.access.domain.RecoveryLimits
import ru.creditcabinet.common.exception.ApiException
import ru.creditcabinet.common.limits.LimitWindow
import ru.creditcabinet.common.limits.SharedLimits
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant

@Component
class RecoveryThrottle(private val jdbc: NamedParameterJdbcTemplate, private val shared: SharedLimits) : RecoveryLimits {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    override fun acquire(email: String, address: String, now: Instant) {
        if (shared.enabled) {
            shared.acquire(
                "Слишком много запросов восстановления. Попробуйте через 15 минут.",
                LimitWindow("recovery-email", email, 3, Duration.ofMinutes(15)),
                LimitWindow("recovery-ip", address, 20, Duration.ofMinutes(15)),
            )
            return
        }
        for ((scope, value, limit) in listOf(Triple("email", email, 3), Triple("ip", address, 20))) {
            val count = jdbc.queryForObject(
                """
                insert into identity_rate_buckets (bucket_key,window_start,attempts) values (:key,:now,1)
                on conflict (bucket_key) do update set
                    attempts=case when identity_rate_buckets.window_start <= :since then 1 else identity_rate_buckets.attempts+1 end,
                    window_start=case when identity_rate_buckets.window_start <= :since then :now else identity_rate_buckets.window_start end
                returning attempts
                """.trimIndent(),
                mapOf("key" to AccessGrant.hash("$scope:$value"), "now" to Timestamp.from(now), "since" to Timestamp.from(now.minusSeconds(900))),
                Int::class.java,
            )!!
            if (count > limit) throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "Слишком много запросов восстановления. Попробуйте через 15 минут.")
        }
        jdbc.update("delete from identity_rate_buckets where window_start < :before", mapOf("before" to Timestamp.from(now.minusSeconds(86400))))
    }
}
