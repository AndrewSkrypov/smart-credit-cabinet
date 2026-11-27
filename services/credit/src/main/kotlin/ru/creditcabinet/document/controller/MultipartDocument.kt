package ru.creditcabinet.document.controller

import org.springframework.web.multipart.MultipartFile
import ru.creditcabinet.document.application.DocumentUpload

fun MultipartFile.toDocumentUpload(operationKey: String? = null): DocumentUpload = DocumentUpload(originalFilename, size, operationKey) { bytes }
