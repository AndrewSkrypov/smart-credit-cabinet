package ru.creditcabinet.document.application

/** Expected transport outage, distinct from invalid content and authorization failures. */
class DocumentsUnavailable : ru.creditcabinet.common.exception.ApiException(
    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
    "Документы временно недоступны",
)
