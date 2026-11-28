package ru.creditcabinet.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.creditcabinet.assistant.application.AnswerMetrics
import ru.creditcabinet.assistant.application.AssistantApplications
import ru.creditcabinet.assistant.application.AssistantNextSteps
import ru.creditcabinet.assistant.application.AssistantProducts
import ru.creditcabinet.assistant.application.AssistantService
import ru.creditcabinet.assistant.application.InvoiceFactSelection
import ru.creditcabinet.assistant.application.InvoiceFactsModel
import ru.creditcabinet.assistant.application.LanguageModel
import ru.creditcabinet.assistant.application.ManualReviewEvents
import ru.creditcabinet.assistant.application.ManualReviewIngress
import ru.creditcabinet.assistant.application.ManualReviewSummaries
import ru.creditcabinet.assistant.application.ReferenceCache
import ru.creditcabinet.assistant.application.ReviewSummaryCache
import ru.creditcabinet.common.mail.MailCapabilities
import ru.creditcabinet.notification.application.AgentNotificationAccessPort
import ru.creditcabinet.notification.application.NotificationDelivery
import ru.creditcabinet.notification.application.NotificationDeliveryTransaction
import ru.creditcabinet.notification.application.NotificationEmailPort
import ru.creditcabinet.notification.application.NotificationMailAvailability
import ru.creditcabinet.notification.application.NotificationPreferences
import ru.creditcabinet.notification.application.NotificationReceipts
import ru.creditcabinet.notification.application.NotificationService
import ru.creditcabinet.notification.application.NotificationSettingsService
import ru.creditcabinet.notification.application.NotificationStore
import ru.creditcabinet.notification.application.PeopleDirectory
import java.time.Clock

@Configuration
class EngagementConfig {
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun assistantService(
        model: LanguageModel,
        applications: AssistantApplications,
        products: AssistantProducts,
        clock: Clock,
        cache: ReferenceCache,
        summaries: ManualReviewSummaries,
        metrics: AnswerMetrics,
    ): AssistantService = AssistantService(model, applications, products, clock, cache, summaries, metrics)

    @Bean
    fun assistantNextSteps(applications: AssistantApplications, notifications: NotificationDelivery, clock: Clock): AssistantNextSteps =
        AssistantNextSteps(applications, notifications, clock)

    @Bean
    fun manualReviewIngress(events: ManualReviewEvents): ManualReviewIngress = ManualReviewIngress(events)

    @Bean
    fun manualReviewSummaries(applications: AssistantApplications, cache: ReviewSummaryCache): ManualReviewSummaries =
        ManualReviewSummaries(applications, cache)

    @Bean
    fun invoiceFactSelection(model: InvoiceFactsModel): InvoiceFactSelection = InvoiceFactSelection(model)

    @Bean
    fun notificationService(
        store: NotificationStore,
        people: PeopleDirectory,
        clock: Clock,
        receipts: NotificationReceipts,
        email: NotificationEmailPort,
        agentAccess: AgentNotificationAccessPort,
        transaction: NotificationDeliveryTransaction,
    ): NotificationService = NotificationService(store, people, clock, receipts, email, agentAccess, transaction)

    @Bean
    fun notificationSettingsService(preferences: NotificationPreferences, mail: MailCapabilities): NotificationSettingsService =
        NotificationSettingsService(preferences, NotificationMailAvailability(mail::enabled))
}
