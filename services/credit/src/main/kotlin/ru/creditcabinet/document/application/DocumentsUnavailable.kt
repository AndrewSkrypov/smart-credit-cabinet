package ru.creditcabinet.document.application

/** Expected transport outage, distinct from invalid content and authorization failures. */
class DocumentsUnavailable : RuntimeException("Документы временно недоступны")

class DocumentUploadsDisabled(message: String = "Загрузка файлов временно приостановлена. Попробуйте позже") : RuntimeException(message)
