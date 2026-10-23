package ru.creditcabinet.common.outbox

import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.test.context.support.TestPropertySourceUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertTrue

class DeliverySchedulingTest {
    @Test
    fun `a blocked receiver cannot occupy the scheduler that advances credit jobs`() {
        val context = AnnotationConfigApplicationContext()
        TestPropertySourceUtils.addInlinedPropertiesToEnvironment(context, "cabinet.outbox.enabled=true")
        context.register(DeliverySchedulingConfig::class.java, Fixture::class.java)
        context.refresh()
        val probe = context.getBean(Probe::class.java)
        try {
            assertTrue(probe.started.await(2, TimeUnit.SECONDS), "delivery started on its dedicated scheduler")
            assertTrue(probe.progress.await(1, TimeUnit.SECONDS), "other scheduled work progressed while delivery was blocked")
        } finally {
            probe.release.countDown()
            context.close()
        }
    }

    @Configuration(proxyBeanMethods = false)
    class Fixture {
        @Bean
        fun probe(): Probe = Probe()
    }

    class Probe {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val progress = CountDownLatch(1)

        @Scheduled(fixedDelay = 10, scheduler = "notificationDeliveryScheduler")
        fun delivery() {
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
        }

        @Scheduled(fixedDelay = 10)
        fun creditProgress() {
            if (started.count == 0L) progress.countDown()
        }
    }
}
