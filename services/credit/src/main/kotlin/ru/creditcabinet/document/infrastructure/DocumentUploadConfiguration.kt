package ru.creditcabinet.document.infrastructure

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.creditcabinet.document.application.DocumentUploadSettings

@Configuration
class DocumentUploadConfiguration {
    @Bean
    fun documentUploadSettings(
        @Value("\${cabinet.storage.max-size-bytes:20971520}") maxSizeBytes: Long,
        @Value("\${cabinet.storage.uploads-enabled:true}") enabled: Boolean,
    ): DocumentUploadSettings = DocumentUploadSettings(maxSizeBytes, enabled)
}
