package ru.creditcabinet.notification

// The assistant consumes this in-process API. HTTP and database adapters stay private to the module.
typealias Audience = ru.creditcabinet.notification.application.Audience
typealias NewNotification = ru.creditcabinet.notification.application.NewNotification
typealias NotificationService = ru.creditcabinet.notification.application.NotificationService
typealias NotificationType = ru.creditcabinet.notification.domain.NotificationType
