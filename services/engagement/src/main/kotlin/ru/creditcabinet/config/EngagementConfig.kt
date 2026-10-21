package ru.creditcabinet.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration
class EngagementConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()
}
