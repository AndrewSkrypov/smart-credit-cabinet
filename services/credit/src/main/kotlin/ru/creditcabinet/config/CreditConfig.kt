package ru.creditcabinet.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class CreditConfig {
    /** One clock for every timestamp of a transition; tests replace it to move time. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
