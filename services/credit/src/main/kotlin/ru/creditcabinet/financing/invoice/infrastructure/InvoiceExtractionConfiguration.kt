package ru.creditcabinet.financing.invoice.infrastructure

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import ru.creditcabinet.financing.invoice.application.InvoiceExtraction
import ru.creditcabinet.financing.invoice.application.InvoiceModel
import ru.creditcabinet.financing.invoice.application.InvoiceTextReader

@Configuration(proxyBeanMethods = false)
internal class InvoiceExtractionConfiguration {
    @Bean("invoiceExtraction")
    internal fun invoiceExtraction(model: InvoiceModel, reader: InvoiceTextReader): InvoiceExtraction = InvoiceExtraction(model, reader)
}
