package ru.creditcabinet.common.outbox

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = ["cabinet.outbox.enabled"], havingValue = "true")
class DeliverySchedulingConfig {
    @Bean("taskScheduler")
    @Primary
    @ConditionalOnMissingBean(name = ["taskScheduler"])
    fun taskScheduler(): ThreadPoolTaskScheduler = ThreadPoolTaskScheduler().apply {
        poolSize = 2
        setThreadNamePrefix("scheduled-")
    }

    @Bean("notificationDeliveryScheduler")
    fun notificationDeliveryScheduler(): ThreadPoolTaskScheduler = ThreadPoolTaskScheduler().apply {
        // Network delivery must never occupy the scheduler that drives credit decisions.
        poolSize = 1
        setThreadNamePrefix("delivery-")
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(15)
    }
}
