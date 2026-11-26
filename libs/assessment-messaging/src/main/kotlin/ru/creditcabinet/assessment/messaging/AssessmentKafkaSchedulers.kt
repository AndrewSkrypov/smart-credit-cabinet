package ru.creditcabinet.assessment.messaging

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler

@Configuration(proxyBeanMethods = false)
class AssessmentKafkaSchedulers {
    @Bean("assessmentPublishScheduler")
    fun assessmentPublishScheduler() = scheduler("assessment-publish-")

    @Bean("assessmentConsumeScheduler")
    fun assessmentConsumeScheduler() = scheduler("assessment-consume-")

    private fun scheduler(prefix: String) = ThreadPoolTaskScheduler().apply {
        poolSize = 1
        setThreadNamePrefix(prefix)
        setWaitForTasksToCompleteOnShutdown(true)
        setAwaitTerminationSeconds(10)
    }
}
